package com.chatai.an.ai.provider;

import android.content.Context;
import android.util.Base64;

import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.Response;

public final class HttpErrorMapper {
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TEXT_FILE_BYTES = 48 * 1024;

    private HttpErrorMapper() {
    }

    public static AiError fromHttpResponse(Response response, String bodySnippet, String providerId) {
        int code = response.code();
        long retryAfter = 0L;
        String retryHeader = response.header("Retry-After");
        if (retryHeader != null && !retryHeader.trim().isEmpty()) {
            try {
                retryAfter = Long.parseLong(retryHeader.trim());
            } catch (NumberFormatException ignored) {
                retryAfter = 60L;
            }
        }

        String lower = bodySnippet != null ? bodySnippet.toLowerCase() : "";
        AiError.ErrorType type;
        if (code == 401 || code == 403) {
            type = AiError.ErrorType.AUTH_ERROR;
        } else if (code == 402 || lower.contains("insufficient_quota") || lower.contains("quota")) {
            type = AiError.ErrorType.QUOTA_EXCEEDED;
        } else if (code == 429) {
            type = AiError.ErrorType.RATE_LIMIT;
            if (retryAfter == 0L) retryAfter = 30L;
        } else if (code == 404 || code >= 500 || lower.contains("no endpoints found") || lower.contains("model is currently unavailable")) {
            type = AiError.ErrorType.UNAVAILABLE;
        } else {
            type = AiError.ErrorType.PROTOCOL_ERROR;
        }

        String summary = (bodySnippet != null && !bodySnippet.trim().isEmpty())
                ? trimSnippet(bodySnippet)
                : ("HTTP " + code + " " + response.message());
        return new AiError(type, summary, code, retryAfter, providerId);
    }

    public static String trimSnippet(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 260 ? cleaned.substring(0, 260) + "..." : cleaned;
    }

    public static String resolveWireModelId(AiModel model) {
        if (model == null || model.getId() == null) return "";
        String id = model.getId();
        if (id.startsWith("pollinations/")) return id.substring("pollinations/".length());
        if (id.startsWith("kilo/")) return id.substring("kilo/".length());
        if (id.startsWith("openrouter_free/")) return id.substring("openrouter_free/".length());
        if (id.startsWith("openrouter/")) return id.substring("openrouter/".length());
        if (id.startsWith("ollama/")) return id.substring("ollama/".length());
        return id;
    }

    public static String normalizeBaseUrl(String rawBaseUrl, String fallback) {
        String candidate = (rawBaseUrl == null || rawBaseUrl.trim().isEmpty()) ? fallback : rawBaseUrl.trim();
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            return fallback;
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    public static String readAttachmentAsBase64(Context context, Attachment att) {
        if (att == null || att.getType() != AttachmentType.IMAGE) return null;
        try {
            byte[] bytes = readBytesLimited(context, att, MAX_IMAGE_BYTES);
            if (bytes == null || bytes.length == 0) return null;
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String readTextAttachmentPreview(Context context, Attachment att) {
        if (att == null || att.getType() == AttachmentType.IMAGE) return null;
        String mime = att.getMimeType() != null ? att.getMimeType().toLowerCase() : "";
        String name = att.getDisplayName() != null ? att.getDisplayName().toLowerCase() : "";
        boolean isTextLike = mime.startsWith("text/")
                || mime.contains("json")
                || mime.contains("xml")
                || mime.contains("csv")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".json")
                || name.endsWith(".csv")
                || name.endsWith(".xml")
                || name.endsWith(".java")
                || name.endsWith(".kt")
                || name.endsWith(".py")
                || name.endsWith(".js");
        if (!isTextLike) {
            return "[Attached file: " + att.getDisplayName() + " (" + att.getMimeType() + ", " + att.getSizeBytes() + " bytes)]";
        }
        try {
            byte[] bytes = readBytesLimited(context, att, MAX_TEXT_FILE_BYTES);
            if (bytes == null || bytes.length == 0) return null;
            return "[File: " + att.getDisplayName() + "]\n" + new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readBytesLimited(Context context, Attachment att, int maxBytes) throws Exception {
        InputStream in = null;
        try {
            if (att.getLocalPath() != null && !att.getLocalPath().isEmpty()) {
                File f = new File(att.getLocalPath());
                if (f.exists() && f.length() <= maxBytes) {
                    in = new FileInputStream(f);
                }
            }
            if (in == null && context != null && att.getUriString() != null && !att.getUriString().isEmpty()) {
                in = context.getContentResolver().openInputStream(android.net.Uri.parse(att.getUriString()));
            }
            if (in == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) return null;
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
