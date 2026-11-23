package com.chatai.an.ai.tool;

import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.SettingsRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Multi-provider Internet Tools:
 * - web_search (DuckDuckGo HTML -> Wikipedia API -> GitHub Repo API fallback chain)
 * - http_get
 * - http_post
 * - fetch_url (plus web_fetch alias kept in AppContainer)
 * - read_webpage (clean article/headings/code extraction)
 * - check_url (status, redirects, size, Accept-Ranges)
 */
public final class InternetTools {
    private static final int MAX_BODY_CHARS = 8000;
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private InternetTools() {
    }

    public static void registerAll(ToolRegistry registry, OkHttpClient client, SettingsRepository settingsRepository) {
        registry.register(new WebSearchTool(client, settingsRepository));
        registry.register(new HttpGetTool(client));
        registry.register(new HttpPostTool(client));
        registry.register(new FetchUrlTool(client));
        registry.register(new ReadWebpageTool(client));
        registry.register(new CheckUrlTool(client));
    }

    public static class WebSearchTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "web_search",
                "Search the Internet using multi-engine fallback (DuckDuckGo, Wikipedia, GitHub repositories/code).",
                "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"},\"engine\":{\"type\":\"string\",\"enum\":[\"auto\",\"duckduckgo\",\"wikipedia\",\"github\"]},\"limit\":{\"type\":\"integer\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.INTERNET,
                false
        );

        private final OkHttpClient client;
        private final SettingsRepository settingsRepository;

        public WebSearchTool(OkHttpClient client, SettingsRepository settingsRepository) {
            this.client = client;
            this.settingsRepository = settingsRepository;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String query = optStr(args, "query");
            if (query.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing search query");
            String engine = optStr(args, "engine").toLowerCase(Locale.ROOT);
            if (engine.isEmpty() && settingsRepository != null) {
                engine = settingsRepository.getSettingsSnapshot().getPreferredSearchEngine().toLowerCase(Locale.ROOT);
            }
            if (engine.isEmpty()) engine = "auto";
            int limit = Math.max(1, Math.min(10, optInt(args, "limit", 6)));

            List<String> errors = new ArrayList<>();
            List<SearchHit> hits = new ArrayList<>();

            if ("github".equals(engine)) {
                hits = searchGitHub(client, query, limit, errors);
            } else if ("wikipedia".equals(engine)) {
                hits = searchWikipedia(client, query, limit, errors);
            } else if ("duckduckgo".equals(engine)) {
                hits = searchDuckDuckGo(client, query, limit, errors);
            } else {
                // Auto fallback chain
                hits = searchDuckDuckGo(client, query, limit, errors);
                if (hits.isEmpty()) {
                    hits = searchGitHub(client, query, Math.min(limit, 5), errors);
                }
                if (hits.isEmpty()) {
                    hits = searchWikipedia(client, query, Math.min(limit, 5), errors);
                }
            }

            if (hits.isEmpty()) {
                return new ToolResult(
                        DEF.getName(),
                        false,
                        "",
                        "No search results obtained. Provider errors: " + String.join(" | ", errors)
                );
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hits.size(); i++) {
                SearchHit h = hits.get(i);
                sb.append(i + 1).append(". [").append(h.source).append("] ").append(h.title).append("\n")
                        .append("   URL: ").append(h.url).append("\n");
                if (!h.snippet.isEmpty()) {
                    sb.append("   ").append(h.snippet).append("\n");
                }
            }
            return new ToolResult(DEF.getName(), true, sb.toString().trim(), "");
        }
    }

    public static class HttpGetTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "http_get",
                "Perform a public HTTP/HTTPS GET request and return status code, headers, and response body.",
                "{\"type\":\"object\",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.INTERNET,
                false
        );
        private final OkHttpClient client;

        public HttpGetTool(OkHttpClient client) {
            this.client = client;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String url = optStr(args, "url");
            String err = WebFetchTool.validatePublicUrl(url);
            if (err != null) return new ToolResult(DEF.getName(), false, "", err);

            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "ChatAI-Android/1.0")
                    .get()
                    .build();
            try (Response res = client.newCall(req).execute()) {
                String body = res.body() != null ? res.body().string() : "";
                if (body.length() > MAX_BODY_CHARS) body = body.substring(0, MAX_BODY_CHARS) + "\n...[truncated]";
                String out = "HTTP " + res.code() + " " + res.message()
                        + "\nContent-Type: " + res.header("Content-Type", "unknown")
                        + "\n\n" + body;
                return new ToolResult(DEF.getName(), res.isSuccessful(), out, res.isSuccessful() ? "" : ("HTTP " + res.code()));
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class HttpPostTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "http_post",
                "Send an HTTP/HTTPS POST request with a JSON or text payload (requires confirmation).",
                "{\"type\":\"object\",\"required\":[\"url\",\"body\"],\"properties\":{\"url\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"},\"content_type\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.INTERNET,
                true
        );
        private final OkHttpClient client;

        public HttpPostTool(OkHttpClient client) {
            this.client = client;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String url = optStr(args, "url");
            String err = WebFetchTool.validatePublicUrl(url);
            if (err != null) return new ToolResult(DEF.getName(), false, "", err);

            String bodyText = optStr(args, "body");
            String ct = optStr(args, "content_type");
            MediaType mt = ct.isEmpty() ? JSON_MEDIA : MediaType.parse(ct);
            if (mt == null) mt = JSON_MEDIA;

            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "ChatAI-Android/1.0")
                    .post(RequestBody.create(bodyText, mt))
                    .build();
            try (Response res = client.newCall(req).execute()) {
                String respBody = res.body() != null ? res.body().string() : "";
                if (respBody.length() > MAX_BODY_CHARS) respBody = respBody.substring(0, MAX_BODY_CHARS) + "\n...[truncated]";
                String out = "HTTP " + res.code() + " " + res.message() + "\n\n" + respBody;
                return new ToolResult(DEF.getName(), res.isSuccessful(), out, res.isSuccessful() ? "" : ("HTTP " + res.code()));
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class FetchUrlTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "fetch_url",
                "Fetch raw or cleaned text from a public URL.",
                "{\"type\":\"object\",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.INTERNET,
                false
        );
        private final OkHttpClient client;

        public FetchUrlTool(OkHttpClient client) {
            this.client = client;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            return new HttpGetTool(client).execute(args);
        }
    }

    public static class ReadWebpageTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "read_webpage",
                "Read and extract readable title, headings, text content, and primary links from a webpage.",
                "{\"type\":\"object\",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.INTERNET,
                false
        );
        private final OkHttpClient client;

        public ReadWebpageTool(OkHttpClient client) {
            this.client = client;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String url = optStr(args, "url");
            String err = WebFetchTool.validatePublicUrl(url);
            if (err != null) return new ToolResult(DEF.getName(), false, "", err);

            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) ChatAI/1.0")
                    .get()
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (!res.isSuccessful()) {
                    return new ToolResult(DEF.getName(), false, "", "HTTP " + res.code() + " " + res.message());
                }
                String html = res.body() != null ? res.body().string() : "";
                String extracted = extractReadableHtml(html);
                return new ToolResult(DEF.getName(), true, extracted, "");
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }
    }

    public static class CheckUrlTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "check_url",
                "Check URL reachability, final redirected URL, HTTP status, content length, content type, and range-resume support.",
                "{\"type\":\"object\",\"required\":[\"url\"],\"properties\":{\"url\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.INTERNET,
                false
        );
        private final OkHttpClient client;

        public CheckUrlTool(OkHttpClient client) {
            this.client = client;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String url = optStr(args, "url");
            String err = WebFetchTool.validatePublicUrl(url);
            if (err != null) return new ToolResult(DEF.getName(), false, "", err);

            long t0 = System.currentTimeMillis();
            Request headReq = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "ChatAI-Android/1.0")
                    .head()
                    .build();
            try (Response res = client.newCall(headReq).execute()) {
                long rtt = System.currentTimeMillis() - t0;
                if (res.code() == 405) {
                    Request getReq = new Request.Builder().url(url).header("Range", "bytes=0-0").get().build();
                    try (Response getRes = client.newCall(getReq).execute()) {
                        return formatCheckResponse(getRes, System.currentTimeMillis() - t0);
                    }
                }
                return formatCheckResponse(res, rtt);
            } catch (Exception e) {
                return new ToolResult(DEF.getName(), false, "", e.getMessage());
            }
        }

        private ToolResult formatCheckResponse(Response res, long rttMs) {
            String out = "Status: HTTP " + res.code() + " " + res.message()
                    + "\nFinal URL: " + res.request().url()
                    + "\nContent-Type: " + res.header("Content-Type", "unknown")
                    + "\nContent-Length: " + res.header("Content-Length", "unknown")
                    + "\nAccept-Ranges: " + res.header("Accept-Ranges", "none")
                    + "\nLatency: " + rttMs + " ms";
            return new ToolResult(DEF.getName(), res.isSuccessful() || res.code() == 206, out,
                    (res.isSuccessful() || res.code() == 206) ? "" : ("HTTP " + res.code()));
        }
    }

    // =========================================================================
    // Multi-Engine Search Implementations & HTML Parsing
    // =========================================================================

    static class SearchHit {
        final String source;
        final String title;
        final String url;
        final String snippet;

        SearchHit(String source, String title, String url, String snippet) {
            this.source = source;
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    private static List<SearchHit> searchDuckDuckGo(OkHttpClient client, String query, int limit, List<String> errors) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            Request req = new Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=" + encoded)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    .get()
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    errors.add("DuckDuckGo HTTP " + res.code());
                    return hits;
                }
                String html = res.body().string();
                hits.addAll(parseDuckDuckGoHtml(html, limit));
                if (hits.isEmpty()) errors.add("DuckDuckGo returned 0 parsed results");
            }
        } catch (Exception e) {
            errors.add("DuckDuckGo: " + e.getMessage());
        }
        return hits;
    }

    public static List<SearchHit> parseDuckDuckGoHtml(String html, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        if (html == null || html.isEmpty()) return hits;
        Pattern linkPat = Pattern.compile("<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Pattern snipPat = Pattern.compile("<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>|<div[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
        Matcher lm = linkPat.matcher(html);
        Matcher sm = snipPat.matcher(html);
        while (lm.find() && hits.size() < limit) {
            String rawHref = decodeHtmlEntities(lm.group(1));
            String title = stripTags(lm.group(2));
            String snippet = "";
            if (sm.find()) {
                String g1 = sm.group(1) != null ? sm.group(1) : sm.group(2);
                snippet = stripTags(g1 != null ? g1 : "");
            }
            if (!title.isEmpty() && !rawHref.isEmpty()) {
                hits.add(new SearchHit("DuckDuckGo", title, rawHref, snippet));
            }
        }
        return hits;
    }

    private static List<SearchHit> searchGitHub(OkHttpClient client, String query, int limit, List<String> errors) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            Request req = new Request.Builder()
                    .url("https://api.github.com/search/repositories?q=" + encoded + "&per_page=" + limit)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ChatAI-Android/1.0")
                    .get()
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    errors.add("GitHub HTTP " + res.code());
                    return hits;
                }
                JsonObject root = JsonParser.parseString(res.body().string()).getAsJsonObject();
                JsonArray items = root.has("items") && root.get("items").isJsonArray()
                        ? root.getAsJsonArray("items") : new JsonArray();
                for (JsonElement el : items) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String name = obj.has("full_name") ? obj.get("full_name").getAsString() : "";
                    String url = obj.has("html_url") ? obj.get("html_url").getAsString() : "";
                    String desc = obj.has("description") && !obj.get("description").isJsonNull()
                            ? obj.get("description").getAsString() : "";
                    int stars = obj.has("stargazers_count") ? obj.get("stargazers_count").getAsInt() : 0;
                    hits.add(new SearchHit("GitHub", name + " (★ " + stars + ")", url, desc));
                }
                if (hits.isEmpty()) errors.add("GitHub returned 0 repositories");
            }
        } catch (Exception e) {
            errors.add("GitHub: " + e.getMessage());
        }
        return hits;
    }

    private static List<SearchHit> searchWikipedia(OkHttpClient client, String query, int limit, List<String> errors) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            Request req = new Request.Builder()
                    .url("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=" + limit + "&srsearch=" + encoded)
                    .header("User-Agent", "ChatAI-Android/1.0")
                    .get()
                    .build();
            try (Response res = client.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    errors.add("Wikipedia HTTP " + res.code());
                    return hits;
                }
                JsonObject root = JsonParser.parseString(res.body().string()).getAsJsonObject();
                JsonObject qObj = root.has("query") ? root.getAsJsonObject("query") : null;
                JsonArray arr = qObj != null && qObj.has("search") ? qObj.getAsJsonArray("search") : new JsonArray();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String title = obj.has("title") ? obj.get("title").getAsString() : "";
                    String snip = obj.has("snippet") ? stripTags(obj.get("snippet").getAsString()) : "";
                    String url = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8.name());
                    hits.add(new SearchHit("Wikipedia", title, url, snip));
                }
                if (hits.isEmpty()) errors.add("Wikipedia returned 0 results");
            }
        } catch (Exception e) {
            errors.add("Wikipedia: " + e.getMessage());
        }
        return hits;
    }

    public static String extractReadableHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String title = "";
        Matcher tm = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        if (tm.find()) {
            title = stripTags(tm.group(1));
        }
        String cleaned = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<nav.*?</nav>", " ")
                .replaceAll("(?is)<footer.*?</footer>", " ")
                .replaceAll("(?i)<(br|p|div|li|tr|h[1-6])[^>]*>", "\n");
        String text = decodeHtmlEntities(cleaned.replaceAll("<[^>]+>", " "))
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() > MAX_BODY_CHARS) {
            text = text.substring(0, MAX_BODY_CHARS) + "\n...[truncated]";
        }
        return (title.isEmpty() ? "" : ("# " + title + "\n\n")) + text;
    }

    private static String stripTags(String s) {
        return decodeHtmlEntities(s.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
    }

    private static String decodeHtmlEntities(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    private static String optStr(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString().trim()
                : "";
    }

    private static int optInt(JsonObject args, String key, int def) {
        if (args != null && args.has(key) && args.get(key).isJsonPrimitive()) {
            try { return args.get(key).getAsInt(); } catch (Exception ignored) {}
        }
        return def;
    }
}
