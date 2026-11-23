package com.chatai.an.app;

import android.content.Context;

import com.chatai.an.ai.agent.AgentEngine;
import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.core.ChatSessionCoordinator;
import com.chatai.an.ai.integration.IntegrationDiagnosticsManager;
import com.chatai.an.ai.integration.download.DownloadToolManager;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.integration.net.InternetAccessManager;
import com.chatai.an.ai.integration.adb.EmbeddedAdbManager;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.ai.integration.terminal.AppTerminalManager;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.ai.local.DeviceCapabilityChecker;
import com.chatai.an.ai.local.LocalAiManager;
import com.chatai.an.ai.memory.ConversationCompactor;
import com.chatai.an.ai.memory.MemoryMergeService;
import com.chatai.an.ai.memory.MemoryRetrievalEngine;
import com.chatai.an.ai.provider.FreeAccessManager;
import com.chatai.an.ai.provider.ProviderFactory;
import com.chatai.an.ai.tool.AdbShizukuTools;
import com.chatai.an.ai.tool.AttachmentReaderTool;
import com.chatai.an.ai.tool.CalculatorTool;
import com.chatai.an.ai.tool.DeviceInfoTool;
import com.chatai.an.ai.tool.DownloadTools;
import com.chatai.an.ai.tool.FileSystemTools;
import com.chatai.an.ai.tool.InternetTools;
import com.chatai.an.ai.tool.RecallTool;
import com.chatai.an.ai.tool.RememberTool;
import com.chatai.an.ai.tool.TerminalTools;
import com.chatai.an.ai.tool.TermuxTools;
import com.chatai.an.ai.tool.ToolExecutor;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.ai.tool.WebFetchTool;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.ChatDatabase;
import com.chatai.an.data.local.prefs.PreferencesManager;
import com.chatai.an.data.repository.LocalAgentRepository;
import com.chatai.an.data.repository.LocalConversationRepository;
import com.chatai.an.data.repository.LocalMemoryRepository;
import com.chatai.an.data.repository.LocalMessageRepository;
import com.chatai.an.data.repository.LocalModelStoreRepository;
import com.chatai.an.data.repository.LocalProviderRepository;
import com.chatai.an.data.repository.LocalSettingsRepository;
import com.chatai.an.data.repository.LocalThemeRepository;
import com.chatai.an.data.repository.UnifiedModelRepository;
import com.chatai.an.data.security.AndroidKeyStoreCipher;
import com.chatai.an.data.security.KeyCipher;
import com.chatai.an.domain.repository.AgentRepository;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.LocalModelRepository;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.MessageRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.ProviderRepository;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class AppContainer {
    private final AppExecutors executors;
    private final PreferencesManager preferencesManager;
    private final ChatDatabase chatDatabase;
    private final KeyCipher keyCipher;
    private final OkHttpClient okHttpClient;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ModelRepository modelRepository;
    private final ThemeRepository themeRepository;
    private final SettingsRepository settingsRepository;
    private final ProviderRepository providerRepository;
    private final MemoryRepository memoryRepository;
    private final AgentRepository agentRepository;
    private final LocalModelRepository localModelRepository;

    private final DeviceCapabilityChecker deviceCapabilityChecker;
    private final LocalAiManager localAiManager;
    private final ProviderFactory providerFactory;
    private final FreeAccessManager freeAccessManager;
    private final ContextManager contextManager;
    private final AiCore aiCore;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    private final WorkspaceFileManager workspaceFileManager;
    private final InternetAccessManager internetAccessManager;
    private final AppTerminalManager appTerminalManager;
    private final TermuxIntegrationManager termuxIntegrationManager;
    private final ShizukuIntegrationManager shizukuIntegrationManager;
    private final EmbeddedAdbManager embeddedAdbManager;
    private final DownloadToolManager downloadToolManager;
    private final IntegrationDiagnosticsManager integrationDiagnosticsManager;

    private final MemoryRetrievalEngine memoryRetrievalEngine;
    private final MemoryMergeService memoryMergeService;
    private final ConversationCompactor conversationCompactor;
    private final AgentEngine agentEngine;
    private final ChatSessionCoordinator chatSessionCoordinator;

    public AppContainer(Context context) {
        Context appContext = context.getApplicationContext();
        this.executors = AppExecutors.getInstance();
        this.preferencesManager = new PreferencesManager(appContext);
        this.chatDatabase = ChatDatabase.getInstance(appContext);
        this.keyCipher = new AndroidKeyStoreCipher();
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        this.conversationRepository = new LocalConversationRepository(chatDatabase.conversationDao(), executors);
        this.messageRepository = new LocalMessageRepository(chatDatabase, executors);
        this.modelRepository = new UnifiedModelRepository(preferencesManager);
        this.themeRepository = new LocalThemeRepository(preferencesManager);
        this.settingsRepository = new LocalSettingsRepository(preferencesManager);
        this.providerRepository = new LocalProviderRepository(
                chatDatabase.providerConfigDao(),
                chatDatabase.apiKeyDao(),
                keyCipher,
                executors
        );
        this.memoryRepository = new LocalMemoryRepository(chatDatabase, executors);
        this.agentRepository = new LocalAgentRepository(chatDatabase.agentTaskDao(), executors);
        this.localModelRepository = new LocalModelStoreRepository(chatDatabase.localModelDao(), executors);

        this.deviceCapabilityChecker = new DeviceCapabilityChecker(appContext);
        this.localAiManager = new LocalAiManager(appContext, deviceCapabilityChecker, localModelRepository);
        this.providerFactory = new ProviderFactory(appContext, okHttpClient, localModelRepository, deviceCapabilityChecker);
        this.freeAccessManager = new FreeAccessManager(modelRepository, providerRepository, providerFactory, executors);
        this.contextManager = new ContextManager();
        this.aiCore = new AiCore(providerFactory, providerRepository, modelRepository, freeAccessManager, executors);

        // Real Subsystem & Device Integration Managers
        this.workspaceFileManager = new WorkspaceFileManager(appContext, preferencesManager, settingsRepository);
        this.internetAccessManager = new InternetAccessManager(appContext, okHttpClient);
        this.appTerminalManager = new AppTerminalManager(appContext, workspaceFileManager);
        this.termuxIntegrationManager = new TermuxIntegrationManager(appContext);
        this.shizukuIntegrationManager = new ShizukuIntegrationManager(appContext);
        this.embeddedAdbManager = new EmbeddedAdbManager(appContext);
        this.workspaceFileManager.setShizukuManager(this.shizukuIntegrationManager);
        this.downloadToolManager = new DownloadToolManager(appContext, okHttpClient, workspaceFileManager, settingsRepository);

        // Shared Modular Tool Registry & Safety-Gated Executor
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.register(new CalculatorTool());
        this.toolRegistry.register(new DeviceInfoTool(deviceCapabilityChecker));
        this.toolRegistry.register(new AttachmentReaderTool(appContext));
        this.toolRegistry.register(new WebFetchTool(okHttpClient));
        this.toolRegistry.register(new RememberTool(memoryRepository));
        this.toolRegistry.register(new RecallTool(memoryRepository));
        FileSystemTools.registerAll(this.toolRegistry, this.workspaceFileManager);
        TerminalTools.registerAll(
                this.toolRegistry,
                this.appTerminalManager,
                this.termuxIntegrationManager,
                this.shizukuIntegrationManager,
                this.settingsRepository
        );
        TermuxTools.registerAll(this.toolRegistry, this.termuxIntegrationManager);
        AdbShizukuTools.registerAll(this.toolRegistry, this.shizukuIntegrationManager);
        InternetTools.registerAll(this.toolRegistry, this.okHttpClient, this.settingsRepository);
        DownloadTools.registerAll(this.toolRegistry, this.downloadToolManager);

        this.toolExecutor = new ToolExecutor(this.toolRegistry, this.settingsRepository, this.workspaceFileManager, this.executors);
        this.toolExecutor.setShizukuManager(this.shizukuIntegrationManager);
        this.aiCore.setToolRegistry(this.toolRegistry);
        this.aiCore.setToolExecutor(this.toolExecutor);

        this.integrationDiagnosticsManager = new IntegrationDiagnosticsManager(
                appContext,
                modelRepository,
                settingsRepository,
                toolRegistry,
                workspaceFileManager,
                appTerminalManager,
                termuxIntegrationManager,
                shizukuIntegrationManager,
                internetAccessManager,
                downloadToolManager
        );

        this.memoryRetrievalEngine = new MemoryRetrievalEngine(memoryRepository);
        this.memoryMergeService = new MemoryMergeService(memoryRepository);
        this.conversationCompactor = new ConversationCompactor(aiCore, conversationRepository);

        this.agentEngine = new AgentEngine(
                aiCore,
                contextManager,
                toolRegistry,
                toolExecutor,
                memoryRepository,
                agentRepository,
                settingsRepository,
                executors
        );

        this.chatSessionCoordinator = new ChatSessionCoordinator(
                conversationRepository,
                messageRepository,
                modelRepository,
                themeRepository,
                settingsRepository,
                memoryRepository,
                memoryRetrievalEngine,
                conversationCompactor,
                contextManager,
                aiCore,
                toolRegistry,
                toolExecutor,
                workspaceFileManager,
                executors
        );

        // Dynamically load live free catalogs (Pollinations, Kilo, OpenRouter Free) in background
        this.freeAccessManager.refreshFreeCatalogs(null);
    }

    public AppExecutors getExecutors() {
        return executors;
    }

    public PreferencesManager getPreferencesManager() {
        return preferencesManager;
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public ConversationRepository getConversationRepository() {
        return conversationRepository;
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public ModelRepository getModelRepository() {
        return modelRepository;
    }

    public ThemeRepository getThemeRepository() {
        return themeRepository;
    }

    public SettingsRepository getSettingsRepository() {
        return settingsRepository;
    }

    public ProviderRepository getProviderRepository() {
        return providerRepository;
    }

    public MemoryRepository getMemoryRepository() {
        return memoryRepository;
    }

    public AgentRepository getAgentRepository() {
        return agentRepository;
    }

    public LocalModelRepository getLocalModelRepository() {
        return localModelRepository;
    }

    public DeviceCapabilityChecker getDeviceCapabilityChecker() {
        return deviceCapabilityChecker;
    }

    public LocalAiManager getLocalAiManager() {
        return localAiManager;
    }

    public ProviderFactory getProviderFactory() {
        return providerFactory;
    }

    public FreeAccessManager getFreeAccessManager() {
        return freeAccessManager;
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public AiCore getAiCore() {
        return aiCore;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public ToolExecutor getToolExecutor() {
        return toolExecutor;
    }

    public WorkspaceFileManager getWorkspaceFileManager() {
        return workspaceFileManager;
    }

    public InternetAccessManager getInternetAccessManager() {
        return internetAccessManager;
    }

    public AppTerminalManager getAppTerminalManager() {
        return appTerminalManager;
    }

    public TermuxIntegrationManager getTermuxIntegrationManager() {
        return termuxIntegrationManager;
    }

    public ShizukuIntegrationManager getShizukuIntegrationManager() {
        return shizukuIntegrationManager;
    }

    public EmbeddedAdbManager getEmbeddedAdbManager() {
        return embeddedAdbManager;
    }

    public DownloadToolManager getDownloadToolManager() {
        return downloadToolManager;
    }

    public IntegrationDiagnosticsManager getIntegrationDiagnosticsManager() {
        return integrationDiagnosticsManager;
    }

    public MemoryRetrievalEngine getMemoryRetrievalEngine() {
        return memoryRetrievalEngine;
    }

    public MemoryMergeService getMemoryMergeService() {
        return memoryMergeService;
    }

    public ConversationCompactor getConversationCompactor() {
        return conversationCompactor;
    }

    public AgentEngine getAgentEngine() {
        return agentEngine;
    }

    public ChatSessionCoordinator getChatSessionCoordinator() {
        return chatSessionCoordinator;
    }
}
