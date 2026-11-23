package com.chatai.an.presentation.memory;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.memory.MemoryMergeService;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.repository.MemoryRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.List;

public class MemoryViewModel extends ViewModel {
    public interface ProposalsCallback {
        void onLoaded(List<MemoryMergeService.MergeProposal> proposals);
    }

    private final MemoryRepository memoryRepository;
    private final MemoryMergeService mergeService;
    private final ThemeRepository themeRepository;
    private final AppExecutors executors;
    private final MutableLiveData<String> statusMessage = new MutableLiveData<>(null);

    public MemoryViewModel(
            MemoryRepository memoryRepository,
            MemoryMergeService mergeService,
            ThemeRepository themeRepository,
            AppExecutors executors
    ) {
        this.memoryRepository = memoryRepository;
        this.mergeService = mergeService;
        this.themeRepository = themeRepository;
        this.executors = executors;
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return themeRepository.observeActiveTheme();
    }

    public LiveData<List<MemoryItem>> getAllMemories() {
        return memoryRepository.observeAllMemories();
    }

    public LiveData<String> getStatusMessage() {
        return statusMessage;
    }

    public void clearStatusMessage() {
        statusMessage.setValue(null);
    }

    public void addMemory(MemoryCategory category, String content) {
        memoryRepository.addMemory(category, null, content);
    }

    public void addMemoryFull(
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String content,
            int importance,
            boolean pinned
    ) {
        executors.diskIO().execute(() -> {
            memoryRepository.addMemorySync(category, scope, area, projectId, null, content, importance, pinned);
            statusMessage.postValue("Memory saved");
        });
    }

    public void updateMemory(String id, MemoryCategory category, String content, boolean enabled) {
        memoryRepository.updateMemory(id, category, content, enabled);
    }

    public void updateMemoryFull(
            String id,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String content,
            int importance,
            boolean enabled,
            boolean pinned
    ) {
        memoryRepository.updateMemoryFull(id, category, scope, area, projectId, content, importance, enabled, pinned);
    }

    public void setEnabled(String id, boolean enabled) {
        memoryRepository.setMemoryEnabled(id, enabled);
    }

    public void setPinned(String id, boolean pinned) {
        memoryRepository.setMemoryPinned(id, pinned);
    }

    public void deleteMemory(String id) {
        memoryRepository.deleteMemory(id);
    }

    public void loadMergeProposals(ProposalsCallback callback) {
        executors.diskIO().execute(() -> {
            List<MemoryMergeService.MergeProposal> proposals = mergeService.findMergeProposals();
            executors.postToMain(() -> {
                if (callback != null) callback.onLoaded(proposals);
            });
        });
    }

    public void applyMerge(MemoryMergeService.MergeProposal proposal, String editedContent) {
        executors.diskIO().execute(() -> {
            mergeService.applyMerge(proposal, editedContent);
            statusMessage.postValue("Merged " + proposal.getSources().size() + " memories (originals archived safely)");
        });
    }

    public void restoreMerged(String mergedMemoryId) {
        executors.diskIO().execute(() -> {
            mergeService.restoreMerged(mergedMemoryId);
            statusMessage.postValue("Restored original memories");
        });
    }
}
