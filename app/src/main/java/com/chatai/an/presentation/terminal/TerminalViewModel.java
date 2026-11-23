package com.chatai.an.presentation.terminal;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.ai.integration.terminal.AppTerminalManager;
import com.chatai.an.ai.integration.terminal.TerminalSession;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.TerminalBackend;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.repository.SettingsRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerminalViewModel extends ViewModel {

    private final AppTerminalManager appTerminalManager;
    private final TermuxIntegrationManager termuxManager;
    private final ShizukuIntegrationManager shizukuManager;
    private final SettingsRepository settingsRepository;
    private final ThemeRepository themeRepository;
    private final AppExecutors executors;
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor();

    private final TerminalSession session;
    private final MutableLiveData<CharSequence> outputLiveData = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> runningLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<String> workdirLiveData = new MutableLiveData<>("");
    private final MutableLiveData<TerminalBackend> backendLiveData = new MutableLiveData<>(TerminalBackend.APP_TERMINAL);

    public TerminalViewModel(
            AppTerminalManager appTerminalManager,
            TermuxIntegrationManager termuxManager,
            SettingsRepository settingsRepository,
            ThemeRepository themeRepository,
            AppExecutors executors
    ) {
        this(appTerminalManager, termuxManager, null, settingsRepository, themeRepository, executors);
    }

    public TerminalViewModel(
            AppTerminalManager appTerminalManager,
            TermuxIntegrationManager termuxManager,
            ShizukuIntegrationManager shizukuManager,
            SettingsRepository settingsRepository,
            ThemeRepository themeRepository,
            AppExecutors executors
    ) {
        this.appTerminalManager = appTerminalManager;
        this.termuxManager = termuxManager;
        this.shizukuManager = shizukuManager;
        this.settingsRepository = settingsRepository;
        this.themeRepository = themeRepository;
        this.executors = executors;

        AppSettings snapshot = settingsRepository.getSettingsSnapshot();
        TerminalBackend initialBackend = snapshot != null
                ? snapshot.getPreferredTerminalBackend()
                : TerminalBackend.APP_TERMINAL;

        this.session = new TerminalSession(appTerminalManager, termuxManager, shizukuManager, initialBackend);
        this.session.setListener(new TerminalSession.SessionListener() {
            @Override
            public void onOutputChanged(CharSequence fullOutput) {
                outputLiveData.postValue(fullOutput);
            }

            @Override
            public void onStateChanged(boolean running, String currentWorkdir, TerminalBackend backend) {
                runningLiveData.postValue(running);
                workdirLiveData.postValue(currentWorkdir);
                backendLiveData.postValue(backend);
            }
        });
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return themeRepository.observeActiveTheme();
    }

    public LiveData<AppSettings> getSettings() {
        return settingsRepository.observeSettings();
    }

    public LiveData<TermuxIntegrationManager.TermuxState> getTermuxState() {
        return termuxManager.observeState();
    }

    public LiveData<ShizukuIntegrationManager.ShizukuState> getShizukuState() {
        return shizukuManager != null ? shizukuManager.observeState() : new MutableLiveData<>();
    }

    public LiveData<AppTerminalManager.TerminalState> getAppTerminalState() {
        return appTerminalManager.observeState();
    }

    public LiveData<CharSequence> getOutput() {
        return outputLiveData;
    }

    public LiveData<Boolean> isRunning() {
        return runningLiveData;
    }

    public LiveData<String> getWorkdir() {
        return workdirLiveData;
    }

    public LiveData<TerminalBackend> getActiveBackend() {
        return backendLiveData;
    }

    public void selectBackend(TerminalBackend backend) {
        TerminalBackend safe = backend != null ? backend : TerminalBackend.APP_TERMINAL;
        settingsRepository.setPreferredTerminalBackend(safe);
        session.setActiveBackend(safe);
        refreshBackendStates();
    }

    public void refreshBackendStates() {
        executors.diskIO().execute(() -> {
            appTerminalManager.refreshState();
            termuxManager.refreshState();
            if (shizukuManager != null) {
                shizukuManager.refreshState();
            }
        });
    }

    public void executeCommand(String command) {
        if (command == null || command.trim().isEmpty()) return;
        int timeoutSec = settingsRepository.getSettingsSnapshot().getToolTimeoutSeconds();
        if (session.isRunning()) {
            executors.worker().execute(() -> session.sendStdinLine(command));
        } else {
            sessionExecutor.execute(() -> session.submitCommand(command, Math.max(30, timeoutSec)));
        }
    }

    public void interrupt() {
        executors.worker().execute(session::interrupt);
    }

    public void clearConsole() {
        session.clearConsole();
    }

    public String navigateHistory(boolean previous) {
        return session.navigateHistory(previous);
    }

    public String completeInput(String currentInput) {
        return session.completeInput(currentInput);
    }

    public String getPlainTextSnapshot() {
        CharSequence snap = outputLiveData.getValue();
        return snap != null ? snap.toString() : "";
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        session.interrupt();
        sessionExecutor.shutdownNow();
    }
}
