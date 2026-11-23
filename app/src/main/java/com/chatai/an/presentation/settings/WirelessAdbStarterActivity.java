package com.chatai.an.presentation.settings;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.chatai.an.R;
import com.chatai.an.ai.integration.adb.ChatAiServerManager;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.databinding.ActivityWirelessAdbStarterBinding;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dark/Gray Console screen that launches ChatAiServerMain (UID 2000) via Wireless Debugging.
 * Emits starter execution logs and automatically closes back to SettingsActivity upon success.
 */
public class WirelessAdbStarterActivity extends AppCompatActivity {

    private static final long AUTO_CLOSE_DELAY_MS = 950L;

    private ActivityWirelessAdbStarterBinding binding;
    private ChatAiServerManager serverManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean destroyed = false;

    private final Runnable autoCloseRunnable = () -> {
        if (!destroyed && !isFinishing()) {
            setResult(RESULT_OK);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityWirelessAdbStarterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.starterRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        serverManager = app.getAppContainer().getChatAiServerManager();

        binding.btnStarterBack.setOnClickListener(v -> finish());
        binding.btnStarterClose.setOnClickListener(v -> finish());
        binding.btnStarterRetry.setOnClickListener(v -> runStarter());

        runStarter();
    }

    private void runStarter() {
        if (!running.compareAndSet(false, true)) return;

        mainHandler.removeCallbacks(autoCloseRunnable);
        logBuffer.clear();
        binding.tvStarterConsole.setText("");
        binding.progressStarter.setVisibility(View.VISIBLE);
        binding.llStarterActions.setVisibility(View.GONE);
        binding.tvStarterSubtitle.setText(R.string.wireless_adb_status_starting);

        AppExecutors.getInstance().worker().execute(() -> {
            boolean ok = serverManager.startServerWithConsole((line, isError) ->
                    mainHandler.post(() -> appendLogLine(line, isError))
            );
            mainHandler.post(() -> {
                running.set(false);
                if (destroyed || isFinishing()) return;
                binding.progressStarter.setVisibility(View.GONE);
                if (ok) {
                    binding.tvStarterSubtitle.setText(R.string.wireless_adb_status_running);
                    binding.tvStarterSubtitle.setTextColor(Color.parseColor("#10B981"));
                    mainHandler.postDelayed(autoCloseRunnable, AUTO_CLOSE_DELAY_MS);
                } else {
                    binding.tvStarterSubtitle.setText(R.string.wireless_adb_status_stopped);
                    binding.tvStarterSubtitle.setTextColor(Color.parseColor("#EF4444"));
                    binding.llStarterActions.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void appendLogLine(String line, boolean isError) {
        if (destroyed || binding == null) return;
        int start = logBuffer.length();
        logBuffer.append(line).append('\n');
        int end = logBuffer.length();
        int color = isError
                ? Color.parseColor("#F87171")
                : (line.contains("chatai_server started") || line.contains("exit with code 0")
                ? Color.parseColor("#34D399")
                : Color.parseColor("#E5E7EB"));
        logBuffer.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.tvStarterConsole.setText(logBuffer);
        binding.scrollStarterLogs.post(() -> binding.scrollStarterLogs.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(autoCloseRunnable);
        super.onDestroy();
    }
}
