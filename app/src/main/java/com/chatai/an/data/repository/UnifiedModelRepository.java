package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.data.local.prefs.PreferencesManager;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.FreeProviderStatus;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ModelPricingType;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.repository.ModelRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UnifiedModelRepository implements ModelRepository {
    private final PreferencesManager prefs;
    private final List<AiModel> catalog = new ArrayList<>();
    private final MutableLiveData<List<AiModel>> allModelsLiveData = new MutableLiveData<>();
    private final MutableLiveData<AiModel> selectedModelLiveData = new MutableLiveData<>();
    private final Map<ModelProvider, FreeProviderStatus> freeStatusMap = new EnumMap<>(ModelProvider.class);
    private final MutableLiveData<Map<ModelProvider, FreeProviderStatus>> freeStatusLiveData = new MutableLiveData<>();

    public UnifiedModelRepository(PreferencesManager prefs) {
        this.prefs = prefs;
        synchronized (catalog) {
            catalog.addAll(buildInitialCatalog());
        }
        long now = System.currentTimeMillis();
        freeStatusMap.put(ModelProvider.POLLINATIONS, new FreeProviderStatus(
                ModelProvider.POLLINATIONS,
                FreeProviderStatus.State.AVAILABLE,
                "Ready (Free tier)",
                0L,
                countProviderModels(ModelProvider.POLLINATIONS),
                now
        ));
        freeStatusMap.put(ModelProvider.KILO, new FreeProviderStatus(
                ModelProvider.KILO,
                FreeProviderStatus.State.AVAILABLE,
                "Ready (Verified pricing=0 models)",
                0L,
                countProviderModels(ModelProvider.KILO),
                now
        ));
        freeStatusMap.put(ModelProvider.OPENROUTER_FREE, new FreeProviderStatus(
                ModelProvider.OPENROUTER_FREE,
                FreeProviderStatus.State.TEMPORARILY_UNAVAILABLE,
                "Loading dynamic catalog (tap Sync if offline)",
                0L,
                0,
                now
        ));
        freeStatusLiveData.setValue(Collections.unmodifiableMap(new EnumMap<>(freeStatusMap)));
        allModelsLiveData.setValue(getAllModels());
        selectedModelLiveData.setValue(resolveModel(prefs.getSelectedModelId()));
    }

    @Override
    public LiveData<List<AiModel>> observeAllModels() {
        return allModelsLiveData;
    }

    @Override
    public List<AiModel> getAllModels() {
        synchronized (catalog) {
            return Collections.unmodifiableList(new ArrayList<>(catalog));
        }
    }

    @Override
    public Map<ModelProvider, List<AiModel>> getGroupedModels() {
        Map<ModelProvider, List<AiModel>> grouped = new LinkedHashMap<>();
        for (ModelProvider provider : ModelProvider.values()) {
            grouped.put(provider, new ArrayList<>());
        }
        synchronized (catalog) {
            for (AiModel model : catalog) {
                List<AiModel> list = grouped.get(model.getProvider());
                if (list != null) {
                    list.add(model);
                }
            }
        }
        return Collections.unmodifiableMap(grouped);
    }

    @Override
    public AiModel getModelById(String modelId) {
        return resolveModel(modelId);
    }

    @Override
    public LiveData<AiModel> observeSelectedModel() {
        return selectedModelLiveData;
    }

    @Override
    public AiModel getSelectedModelSnapshot() {
        AiModel value = selectedModelLiveData.getValue();
        return value != null ? value : getAllModels().get(0);
    }

    @Override
    public void selectModel(String modelId) {
        AiModel found = resolveModel(modelId);
        prefs.setSelectedModelId(found.getId());
        selectedModelLiveData.postValue(found);
    }

    @Override
    public void upsertDynamicModels(ModelProvider provider, List<AiModel> fetchedModels) {
        if (provider == null || fetchedModels == null || fetchedModels.isEmpty()) return;
        synchronized (catalog) {
            Iterator<AiModel> iterator = catalog.iterator();
            while (iterator.hasNext()) {
                AiModel existing = iterator.next();
                if (existing.getProvider() == provider && !"builtin".equals(existing.getCatalogSource())) {
                    iterator.remove();
                }
            }
            for (AiModel fetched : fetchedModels) {
                boolean replaced = false;
                for (int i = 0; i < catalog.size(); i++) {
                    if (catalog.get(i).getId().equals(fetched.getId())) {
                        catalog.set(i, fetched);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    catalog.add(fetched);
                }
            }
        }
        List<AiModel> snapshot = getAllModels();
        allModelsLiveData.postValue(snapshot);
        AiModel current = selectedModelLiveData.getValue();
        if (current != null) {
            selectedModelLiveData.postValue(resolveModel(current.getId()));
        }
    }

    @Override
    public LiveData<Map<ModelProvider, FreeProviderStatus>> observeFreeProviderStatuses() {
        return freeStatusLiveData;
    }

    @Override
    public void updateFreeProviderStatus(FreeProviderStatus status) {
        if (status == null || status.getProvider() == null) return;
        synchronized (freeStatusMap) {
            freeStatusMap.put(status.getProvider(), status);
            freeStatusLiveData.postValue(Collections.unmodifiableMap(new EnumMap<>(freeStatusMap)));
        }
    }

    private int countProviderModels(ModelProvider provider) {
        int count = 0;
        synchronized (catalog) {
            for (AiModel m : catalog) {
                if (m.getProvider() == provider) count++;
            }
        }
        return count;
    }

    private AiModel resolveModel(String modelId) {
        synchronized (catalog) {
            if (modelId != null) {
                for (AiModel model : catalog) {
                    if (model.getId().equals(modelId)) {
                        return model;
                    }
                }
            }
            return catalog.get(0);
        }
    }

    public static Map<ModelCapability, CapabilityStatus> caps(
            CapabilityStatus text,
            CapabilityStatus image,
            CapabilityStatus file,
            CapabilityStatus deepThinking,
            CapabilityStatus streaming,
            CapabilityStatus toolCalling,
            CapabilityStatus structuredOutput
    ) {
        EnumMap<ModelCapability, CapabilityStatus> map = new EnumMap<>(ModelCapability.class);
        map.put(ModelCapability.TEXT, text);
        map.put(ModelCapability.IMAGE, image);
        map.put(ModelCapability.FILE, file);
        map.put(ModelCapability.DEEP_THINKING, deepThinking);
        map.put(ModelCapability.STREAMING, streaming);
        map.put(ModelCapability.TOOL_CALLING, toolCalling);
        map.put(ModelCapability.STRUCTURED_OUTPUT, structuredOutput);
        return map;
    }

    private static List<AiModel> buildInitialCatalog() {
        long now = System.currentTimeMillis();
        List<AiModel> models = new ArrayList<>();

        // === FREE CLOUD AI PROVIDERS ===
        models.add(new AiModel(
                "pollinations/openai",
                "OpenAI GPT-4o Mini (Free)",
                ModelProvider.POLLINATIONS,
                ModelPricingType.FREE,
                AccessType.FREE,
                ProtocolType.POLLINATIONS_TEXT,
                "Free no-key access via Pollinations text gateway",
                "128K ctx",
                128000,
                8192,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "pollinations/openai-reasoning",
                "OpenAI o3-mini Reasoning (Free)",
                ModelProvider.POLLINATIONS,
                ModelPricingType.FREE,
                AccessType.FREE,
                ProtocolType.POLLINATIONS_TEXT,
                "Free reasoning model via Pollinations",
                "128K ctx",
                128000,
                16384,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "pollinations/mistral",
                "Mistral Small 3.1 (Free)",
                ModelProvider.POLLINATIONS,
                ModelPricingType.FREE,
                AccessType.FREE,
                ProtocolType.POLLINATIONS_TEXT,
                "Fast European open-weights chat via Pollinations",
                "128K ctx",
                128000,
                8192,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "kilo/openrouter/free",
                "Kilo Auto Free Router",
                ModelProvider.KILO,
                ModelPricingType.FREE,
                AccessType.FREE,
                ProtocolType.KILO_FREE,
                "Verified zero-price models via Kilo Gateway (refresh for live pricing=0 list)",
                "128K ctx",
                128000,
                8192,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.UNKNOWN),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));

        // === PERSONAL API KEY PROVIDERS ===
        models.add(new AiModel(
                "gemini-2.5-flash",
                "Gemini 2.5 Flash",
                ModelProvider.GOOGLE_GEMINI,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.GEMINI_API,
                "Fast multimodal Gemini model with adaptive thinking",
                "1M ctx",
                1000000,
                65536,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "gemini-2.5-pro",
                "Gemini 2.5 Pro",
                ModelProvider.GOOGLE_GEMINI,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.GEMINI_API,
                "Flagship Gemini model for coding and deep reasoning",
                "2M ctx",
                2000000,
                65536,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "gpt-4o-mini",
                "GPT-4o mini",
                ModelProvider.OPENAI,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "Fast OpenAI multimodal model (requires OpenAI key)",
                "128K ctx",
                128000,
                16384,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "o3-mini",
                "OpenAI o3-mini",
                ModelProvider.OPENAI,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "STEM and coding reasoning model",
                "200K ctx",
                200000,
                100000,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "claude-sonnet-4-20250514",
                "Claude Sonnet 4",
                ModelProvider.ANTHROPIC,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.ANTHROPIC_MESSAGES,
                "Nuanced architecture, coding, and extended thinking",
                "200K ctx",
                200000,
                16384,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "openrouter/openai/gpt-4o-mini",
                "GPT-4o Mini (OpenRouter)",
                ModelProvider.OPENROUTER,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "OpenRouter Personal API model (test connection in Providers to fetch full paid catalog)",
                "128K ctx",
                128000,
                16384,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "llama-3.3-70b-versatile",
                "Llama 3.3 70B (Groq)",
                ModelProvider.GROQ,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "Ultra-fast open weights on Groq LPU",
                "128K ctx",
                128000,
                32768,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "deepseek-reasoner",
                "DeepSeek R1 (Direct)",
                ModelProvider.DEEPSEEK,
                ModelPricingType.API_KEY_REQUIRED,
                AccessType.PERSONAL_API,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "DeepSeek direct API with reasoning trace",
                "64K ctx",
                64000,
                8192,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));
        models.add(new AiModel(
                "ollama/llama3.2",
                "Ollama Local Server (llama3.2)",
                ModelProvider.OLLAMA,
                ModelPricingType.FREE,
                AccessType.PERSONAL_API,
                ProtocolType.OPENAI_CHAT_COMPLETIONS,
                "Connects to your local Ollama /v1 server (set Base URL in Provider Settings)",
                "32K ctx",
                32768,
                4096,
                true,
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNKNOWN, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNKNOWN),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED)
        ));

        // === ON-DEVICE LOCAL AI (Inactive until a real local inference runtime is connected) ===
        models.add(new AiModel(
                "local/device-runtime",
                "On-Device Local Runtime",
                ModelProvider.LOCAL,
                ModelPricingType.FREE,
                AccessType.LOCAL,
                ProtocolType.LOCAL_RUNTIME,
                "No native inference runtime connected in this build. Use Ollama or LM Studio under Personal API for local models.",
                "Inactive",
                16384,
                2048,
                false,
                AiModel.UsabilityStatus.NO_LOCAL_RUNTIME,
                "Inactive • No local runtime connected",
                "builtin",
                now,
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED),
                caps(CapabilityStatus.SUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED, CapabilityStatus.UNSUPPORTED)
        ));

        return models;
    }
}
