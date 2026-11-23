package com.chatai.an.ai.tool;

import android.content.Context;

import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Safely lists or reads text files confined strictly inside ChatAI's private attachments folder.
 */
public class AttachmentReaderTool implements AiTool {
    private static final ToolDefinition DEF = new ToolDefinition(
            "list_attachments",
            "Lists imported local chat attachment filenames inside app sandbox storage, or reads a named text attachment.",
            "{\"type\":\"object\",\"properties\":{\"filename\":{\"type\":\"string\"}}}",
            ToolPermission.AUTO_ALLOW
    );

    private final Context context;

    public AttachmentReaderTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public ToolResult execute(JsonObject arguments) {
        try {
            File baseDir = new File(context.getFilesDir(), "attachments").getCanonicalFile();
            if (!baseDir.exists() || !baseDir.isDirectory()) {
                return new ToolResult(DEF.getName(), true, "No stored attachments found.", "");
            }

            String filename = (arguments != null && arguments.has("filename"))
                    ? arguments.get("filename").getAsString().trim()
                    : "";

            if (filename.isEmpty()) {
                File[] files = baseDir.listFiles();
                if (files == null || files.length == 0) {
                    return new ToolResult(DEF.getName(), true, "No stored attachments found.", "");
                }
                StringBuilder sb = new StringBuilder("Stored attachments:\n");
                for (File f : files) {
                    sb.append("- ").append(f.getName()).append(" (").append(f.length()).append(" bytes)\n");
                }
                return new ToolResult(DEF.getName(), true, sb.toString().trim(), "");
            }

            // Confinement check against path traversal
            File target = new File(baseDir, filename).getCanonicalFile();
            String basePrefix = baseDir.getPath() + File.separator;
            if (!target.getPath().startsWith(basePrefix)) {
                return new ToolResult(DEF.getName(), false, "", "Access denied: path outside attachments directory");
            }
            if (!target.exists() || !target.isFile()) {
                return new ToolResult(DEF.getName(), false, "", "Attachment not found: " + filename);
            }
            if (target.length() > 64 * 1024) {
                return new ToolResult(DEF.getName(), false, "", "Attachment too large for inline reading (" + target.length() + " bytes)");
            }
            byte[] bytes = Files.readAllBytes(target.toPath());
            return new ToolResult(DEF.getName(), true, new String(bytes, StandardCharsets.UTF_8), "");
        } catch (Exception e) {
            return new ToolResult(DEF.getName(), false, "", e.getMessage());
        }
    }
}
