package com.chatai.an.presentation.settings;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.chatai.an.R;
import com.chatai.an.core.LogSenderManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;

public class LogViewerActivity extends AppCompatActivity {

    public static final String EXTRA_LOG_CONTENT = "extra_log_content";
    public static final String EXTRA_LOG_TITLE = "extra_log_title";

    private MaterialToolbar toolbar;
    private TextView tvLogContent;
    private MaterialButton btnExportLogs;
    private MaterialButton btnClearLogs;

    private String logContent = "";
    private String logTitle = "BuildLog";
    private LogSenderManager logSenderManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        logSenderManager = new LogSenderManager(this);

        if (getIntent() != null) {
            logContent = getIntent().getStringExtra(EXTRA_LOG_CONTENT);
            if (logContent == null) logContent = "No log data provided.";
            String title = getIntent().getStringExtra(EXTRA_LOG_TITLE);
            if (title != null && !title.isEmpty()) {
                logTitle = title;
            }
        }

        toolbar = findViewById(R.id.toolbarLogViewer);
        tvLogContent = findViewById(R.id.tvLogContent);
        btnExportLogs = findViewById(R.id.btnExportLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Logs: " + logTitle);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvLogContent.setText(logContent);

        btnExportLogs.setOnClickListener(v -> {
            File f = logSenderManager.exportLogsToFile(logContent, logTitle);
            if (f != null) {
                logSenderManager.shareLogFile(f);
            }
        });

        btnClearLogs.setOnClickListener(v -> {
            logContent = "";
            tvLogContent.setText("Logs cleared.");
        });
    }
}
