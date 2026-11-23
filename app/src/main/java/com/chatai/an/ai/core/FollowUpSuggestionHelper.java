package com.chatai.an.ai.core;

import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.ToolCallRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and strips optional <chatai_followups>[...]</chatai_followups> hints emitted by models
 * and synthesizes concise, mode-aware follow-up chips when none are returned.
 */
public final class FollowUpSuggestionHelper {

    public static final Pattern FOLLOW_UP_PATTERN = Pattern.compile(
            "<chatai_followups>\\s*(\\[.*?\\])\\s*</chatai_followups>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRAILING_OPEN_FOLLOWUP = Pattern.compile(
            "<chatai_followups[\\s\\S]*$",
            Pattern.CASE_INSENSITIVE
    );

    private FollowUpSuggestionHelper() {
    }

    public static List<String> extractSuggestions(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = FOLLOW_UP_PATTERN.matcher(rawText);
        while (matcher.find()) {
            String jsonArrayStr = matcher.group(1);
            try {
                JsonArray arr = JsonParser.parseString(jsonArrayStr).getAsJsonArray();
                for (JsonElement el : arr) {
                    if (el != null && el.isJsonPrimitive()) {
                        String s = el.getAsString().trim();
                        if (!s.isEmpty() && s.length() <= 80) {
                            unique.add(s);
                            if (unique.size() >= 3) break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return unique.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(unique));
    }

    public static String stripFollowUpTags(String text) {
        if (text == null || text.isEmpty()) return "";
        String stripped = FOLLOW_UP_PATTERN.matcher(text).replaceAll("");
        stripped = TRAILING_OPEN_FOLLOWUP.matcher(stripped).replaceAll("");
        return stripped.trim();
    }

    public static List<String> resolveOrSynthesize(
            String rawContent,
            String cleanContent,
            AgentMode mode,
            List<ToolCallRecord> toolRecords,
            boolean vietnamese
    ) {
        return resolveOrSynthesize(rawContent, cleanContent, "", mode, toolRecords, vietnamese);
    }

    public static List<String> resolveOrSynthesize(
            String rawContent,
            String cleanContent,
            String userPrompt,
            AgentMode mode,
            List<ToolCallRecord> toolRecords,
            boolean vietnamese
    ) {
        if (isLikelyTranslationPrompt(userPrompt)) {
            return Collections.emptyList();
        }
        List<String> extracted = extractSuggestions(rawContent);
        if (!extracted.isEmpty()) {
            return extracted;
        }
        if (cleanContent == null || cleanContent.trim().length() < 24) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>();
        String lower = cleanContent.toLowerCase(Locale.ROOT);
        boolean usedShizuku = false;
        boolean hadToolFailure = false;
        boolean hadAnyTools = false;
        if (toolRecords != null) {
            for (ToolCallRecord rec : toolRecords) {
                if (rec == null) continue;
                hadAnyTools = true;
                if (rec.getToolName().startsWith("adb_") || rec.getPrivilegeBadge().toLowerCase(Locale.ROOT).contains("shizuku")) {
                    usedShizuku = true;
                }
                if (rec.getStatus() == ToolCallRecord.Status.FAILED || rec.getStatus() == ToolCallRecord.Status.DENIED) {
                    hadToolFailure = true;
                }
            }
        }

        if (hadToolFailure) {
            out.add(vietnamese ? "Chẩn đoán nguyên nhân lỗi và thử cách khác" : "Diagnose the error and try another approach");
            out.add(vietnamese ? "Kiểm tra quyền hoặc trạng thái Shizuku" : "Check permissions and Shizuku status");
            return out;
        }

        if (mode == AgentMode.CODING) {
            if (usedShizuku) {
                out.add(vietnamese ? "Xem logcat mới nhất của ứng dụng" : "Inspect latest logcat entries");
                out.add(vietnamese ? "Kiểm tra danh sách tiến trình đang chạy" : "Check running processes");
            } else {
                out.add(vietnamese ? "Chạy kiểm tra hoặc build để xác minh" : "Run verification tests or build");
                out.add(vietnamese ? "Tóm tắt các thay đổi vừa thực hiện" : "Summarize recent changes");
            }
            out.add(vietnamese ? "Tối ưu hoặc refactored gọn hơn" : "Refactor for edge cases");
        } else {
            boolean mentionsCode = lower.contains("```") || lower.contains("gradle") || lower.contains("shizuku") || lower.contains("code");
            if (mentionsCode) {
                out.add(vietnamese ? "Giải thích chi tiết từng bước" : "Explain step by step");
                out.add(vietnamese ? "Áp dụng thay đổi này trong chế độ Code" : "Apply this in Code mode");
            } else if (hadAnyTools || cleanContent.trim().length() >= 220) {
                out.add(vietnamese ? "Tóm tắt thành các gạch đầu dòng ngắn" : "Summarize as concise bullet points");
                out.add(vietnamese ? "Đưa ra ví dụ thực tế" : "Show a concrete example");
            } else {
                return Collections.emptyList();
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static boolean isLikelyTranslationPrompt(String userPrompt) {
        if (userPrompt == null) return false;
        String lower = userPrompt.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        return lower.startsWith("dịch")
                || lower.startsWith("translate")
                || lower.contains("dịch sang")
                || lower.contains("dịch câu")
                || lower.contains("dịch đoạn")
                || lower.contains("dịch giúp")
                || lower.contains("translate to ")
                || lower.contains("translate into ")
                || lower.contains("in english")
                || lower.contains("in vietnamese")
                || lower.contains("sang tiếng ");
    }
}
