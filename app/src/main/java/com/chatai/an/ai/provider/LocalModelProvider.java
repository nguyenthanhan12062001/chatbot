package com.chatai.an.ai.provider;

import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.local.DeviceCapabilityChecker;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.DeviceCompatibility;
import com.chatai.an.domain.model.LocalModelInfo;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.repository.LocalModelRepository;

import java.util.Collections;
import java.util.List;

/**
 * Separated Local AI Provider.
 * Reports honest runtime capabilities: inspects imported GGUF/TFLite models and RAM,
 * and clearly reports when no native on-device JNI runtime is bundled rather than faking responses.
 */
public class LocalModelProvider implements AiProvider {
    private final LocalModelRepository localModelRepository;
    private final DeviceCapabilityChecker capabilityChecker;

    public LocalModelProvider(
            LocalModelRepository localModelRepository,
            DeviceCapabilityChecker capabilityChecker
    ) {
        this.localModelRepository = localModelRepository;
        this.capabilityChecker = capabilityChecker;
    }

    @Override
    public String getProviderId() {
        return ModelProvider.LOCAL.getId();
    }

    @Override
    public AccessType getAccessType() {
        return AccessType.LOCAL;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.LOCAL_RUNTIME;
    }

    @Override
    public void execute(AiRequest request, String apiKey, ExecutionHandle handle, AiStreamListener listener) {
        DeviceCompatibility compat = capabilityChecker.inspect();
        List<LocalModelInfo> models = localModelRepository.getLocalModelsSync();
        String message;
        if (models.isEmpty()) {
            message = "No local model file imported yet (" + compat.getAvailableRamMb() + " MB RAM free, "
                    + compat.getPrimaryAbi() + "). Import a .gguf/.tflite file or configure Ollama/LM Studio in Provider Settings.";
        } else {
            LocalModelInfo first = models.get(0);
            message = "Local model \"" + first.getDisplayName() + "\" (" + first.getFormat()
                    + ") is indexed on device, but this APK does not bundle a native GGUF JNI inference engine. "
                    + "Connect Ollama / LM Studio via Provider Settings or switch to a Free Cloud model.";
        }
        listener.onError(new AiError(
                AiError.ErrorType.UNAVAILABLE,
                message,
                0,
                0,
                getProviderId()
        ));
    }

    @Override
    public List<AiModel> fetchModels(String apiKey) {
        return Collections.emptyList();
    }

    @Override
    public String testConnection(String apiKey) {
        DeviceCompatibility compat = capabilityChecker.inspect();
        return "Device RAM: " + compat.getAvailableRamMb() + "/" + compat.getTotalRamMb()
                + " MB | Cores: " + compat.getCpuCores() + " | ABI: " + compat.getPrimaryAbi();
    }
}
