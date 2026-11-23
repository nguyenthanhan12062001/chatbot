package com.chatai.an.ai.tool;

import com.chatai.an.ai.integration.download.DownloadToolManager;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

/**
 * Download Tool enabling AI to fetch documents, ZIPs, source archives, media, libraries, or APKs
 * with progress tracking, Range resume, and SHA-256 verification. Never auto-executes/installs files.
 */
public final class DownloadTools {
    private DownloadTools() {
    }

    public static void registerAll(ToolRegistry registry, DownloadToolManager downloadManager) {
        registry.register(new DownloadFileTool(downloadManager));
    }

    public static class DownloadFileTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "download_file",
                "Download a file from a public http/https URL to device storage with progress tracking, resume support, and optional SHA-256 check. Never auto-executes or auto-installs.",
                "{\"type\":\"object\",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"},\"filename\":{\"type\":\"string\"},\"destination_dir\":{\"type\":\"string\"},\"expected_sha256\":{\"type\":\"string\"},\"resume\":{\"type\":\"boolean\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.DOWNLOAD,
                true
        );

        private final DownloadToolManager manager;

        public DownloadFileTool(DownloadToolManager manager) {
            this.manager = manager;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String url = optStr(args, "url");
            String validationErr = WebFetchTool.validatePublicUrl(url);
            if (validationErr != null) {
                return new ToolResult(DEF.getName(), false, "", validationErr);
            }
            String filename = optStr(args, "filename");
            String destDir = optStr(args, "destination_dir");
            String expectedSha = optStr(args, "expected_sha256");
            boolean resume = !args.has("resume") || args.get("resume").getAsBoolean();

            try {
                DownloadToolManager.DownloadRecord rec = manager.downloadSync(url, destDir, filename, expectedSha, resume);
                if (rec.status == DownloadToolManager.DownloadStatus.COMPLETED) {
                    String summary = "Downloaded: " + rec.fileName
                            + "\nSaved Path: " + rec.destinationPath
                            + "\nSize: " + rec.downloadedBytes + " bytes"
                            + "\nContent-Type: " + rec.mimeType
                            + "\nResumed: " + rec.resumed
                            + "\nSHA-256: " + rec.sha256Actual
                            + "\nNote: File saved only (not auto-executed or installed).";
                    return new ToolResult(DEF.getName(), true, summary, "");
                }
                return new ToolResult(DEF.getName(), false, "", rec.errorMessage);
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    private static String optStr(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString().trim()
                : "";
    }
}
