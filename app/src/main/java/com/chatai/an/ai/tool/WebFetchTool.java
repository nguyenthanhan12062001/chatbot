package com.chatai.an.ai.tool;

import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.net.InetAddress;
import java.net.URL;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches public HTTPS/HTTP web page text with user confirmation & private/loopback SSRF protection.
 */
public class WebFetchTool implements AiTool {
    private static final int MAX_PREVIEW_CHARS = 3500;
    private static final ToolDefinition DEF = new ToolDefinition(
            "web_fetch",
            "Fetches plain-text content from a public https:// URL (requires user confirmation).",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}",
            ToolPermission.REQUIRE_CONFIRMATION
    );

    private final OkHttpClient client;

    public WebFetchTool(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public ToolResult execute(JsonObject arguments) {
        String rawUrl = (arguments != null && arguments.has("url"))
                ? arguments.get("url").getAsString().trim()
                : "";
        String validationError = validatePublicUrl(rawUrl);
        if (validationError != null) {
            return new ToolResult(DEF.getName(), false, "", validationError);
        }
        try {
            Request req = new Request.Builder()
                    .url(rawUrl)
                    .header("User-Agent", "ChatAI-Android/1.0")
                    .get()
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (!res.isSuccessful()) {
                    return new ToolResult(DEF.getName(), false, "", "HTTP " + res.code() + " " + res.message());
                }
                String body = res.body() != null ? res.body().string() : "";
                String stripped = body
                        .replaceAll("(?s)<script.*?</script>", " ")
                        .replaceAll("(?s)<style.*?</style>", " ")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
                if (stripped.length() > MAX_PREVIEW_CHARS) {
                    stripped = stripped.substring(0, MAX_PREVIEW_CHARS) + "...";
                }
                return new ToolResult(DEF.getName(), true, stripped, "");
            }
        } catch (Exception e) {
            return new ToolResult(DEF.getName(), false, "", e.getMessage());
        }
    }

    public static String validatePublicUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return "Missing URL";
        }
        try {
            URL url = new URL(rawUrl);
            String protocol = url.getProtocol() != null ? url.getProtocol().toLowerCase() : "";
            if (!"https".equals(protocol) && !"http".equals(protocol)) {
                return "Only http/https URLs are allowed";
            }
            String host = url.getHost() != null ? url.getHost().toLowerCase() : "";
            if (host.isEmpty() || "localhost".equals(host) || host.endsWith(".local")) {
                return "Local/loopback hostnames are blocked";
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()) {
                    return "Private or link-local IP addresses are blocked";
                }
            }
            return null;
        } catch (Exception e) {
            return "Invalid URL: " + e.getMessage();
        }
    }
}
