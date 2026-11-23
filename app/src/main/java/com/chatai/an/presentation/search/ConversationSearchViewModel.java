package com.chatai.an.presentation.search;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.repository.ConversationRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.List;

public class ConversationSearchViewModel extends ViewModel {
    private final MutableLiveData<String> queryLiveData = new MutableLiveData<>("");
    private final LiveData<List<Conversation>> searchResults;
    private final LiveData<ThemeConfig> activeTheme;

    public ConversationSearchViewModel(
            ConversationRepository conversationRepository,
            ThemeRepository themeRepository
    ) {
        this.activeTheme = themeRepository.observeActiveTheme();
        this.searchResults = Transformations.switchMap(
                queryLiveData,
                conversationRepository::searchConversations
        );
    }

    public void setQuery(String query) {
        queryLiveData.setValue(query == null ? "" : query);
    }

    public LiveData<List<Conversation>> getSearchResults() {
        return searchResults;
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return activeTheme;
    }
}
