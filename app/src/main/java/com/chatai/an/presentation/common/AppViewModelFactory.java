package com.chatai.an.presentation.common;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.chatai.an.app.AppContainer;
import com.chatai.an.presentation.agent.AgentViewModel;
import com.chatai.an.presentation.chat.MainChatViewModel;
import com.chatai.an.presentation.memory.MemoryViewModel;
import com.chatai.an.presentation.provider.ProviderSettingsViewModel;
import com.chatai.an.presentation.search.ConversationSearchViewModel;
import com.chatai.an.presentation.settings.SettingsViewModel;
import com.chatai.an.presentation.themestudio.ThemeStudioViewModel;

public class AppViewModelFactory implements ViewModelProvider.Factory {
    private final AppContainer container;

    public AppViewModelFactory(AppContainer container) {
        this.container = container;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(MainChatViewModel.class)) {
            return (T) new MainChatViewModel(
                    container.getConversationRepository(),
                    container.getMessageRepository(),
                    container.getModelRepository(),
                    container.getThemeRepository(),
                    container.getSettingsRepository(),
                    container.getMemoryRepository(),
                    container.getMemoryRetrievalEngine(),
                    container.getConversationCompactor(),
                    container.getContextManager(),
                    container.getAiCore(),
                    container.getToolRegistry(),
                    container.getToolExecutor(),
                    container.getWorkspaceFileManager(),
                    container.getExecutors()
            );
        } else if (modelClass.isAssignableFrom(ConversationSearchViewModel.class)) {
            return (T) new ConversationSearchViewModel(
                    container.getConversationRepository(),
                    container.getThemeRepository()
            );
        } else if (modelClass.isAssignableFrom(SettingsViewModel.class)) {
            return (T) new SettingsViewModel(
                    container.getThemeRepository(),
                    container.getModelRepository(),
                    container.getSettingsRepository(),
                    container.getConversationRepository(),
                    container.getIntegrationDiagnosticsManager(),
                    container.getToolRegistry(),
                    container.getWorkspaceFileManager(),
                    container.getDownloadToolManager(),
                    container.getExecutors()
            );
        } else if (modelClass.isAssignableFrom(ThemeStudioViewModel.class)) {
            return (T) new ThemeStudioViewModel(container.getThemeRepository());
        } else if (modelClass.isAssignableFrom(ProviderSettingsViewModel.class)) {
            return (T) new ProviderSettingsViewModel(
                    container.getProviderRepository(),
                    container.getModelRepository(),
                    container.getLocalModelRepository(),
                    container.getThemeRepository(),
                    container.getProviderFactory(),
                    container.getFreeAccessManager(),
                    container.getLocalAiManager(),
                    container.getDeviceCapabilityChecker(),
                    container.getExecutors()
            );
        } else if (modelClass.isAssignableFrom(MemoryViewModel.class)) {
            return (T) new MemoryViewModel(
                    container.getMemoryRepository(),
                    container.getMemoryMergeService(),
                    container.getThemeRepository(),
                    container.getExecutors()
            );
        } else if (modelClass.isAssignableFrom(AgentViewModel.class)) {
            return (T) new AgentViewModel(
                    container.getAgentEngine(),
                    container.getAgentRepository(),
                    container.getModelRepository(),
                    container.getThemeRepository(),
                    container.getToolRegistry(),
                    container.getWorkspaceFileManager(),
                    container.getSettingsRepository(),
                    container.getToolExecutor()
            );
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
