package com.chatai.an.core;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured Markdown parser & formatter for ChatAI messages.
 * Separates prose segments (headings, paragraphs, blockquotes, lists, bold, italic,
 * inline code, clickable links), Markdown tables, and fenced code blocks.
 */
public final class MarkdownRenderer {

    public enum SegmentType {
        TEXT,
        CODE_BLOCK,
        TABLE
    }

    public static class MarkdownSegment {
        private final SegmentType type;
        private final String content;
        private final String language;
        private final List<String> tableHeaders;
        private final List<List<String>> tableRows;

        public MarkdownSegment(SegmentType type, String content, String language) {
            this(type, content, language, Collections.emptyList(), Collections.emptyList());
        }

        public MarkdownSegment(
                SegmentType type,
                String content,
                String language,
                List<String> tableHeaders,
                List<List<String>> tableRows
        ) {
            this.type = type;
            this.content = content != null ? content : "";
            this.language = language != null ? language : "";
            this.tableHeaders = tableHeaders != null ? Collections.unmodifiableList(tableHeaders) : Collections.emptyList();
            this.tableRows = tableRows != null ? Collections.unmodifiableList(tableRows) : Collections.emptyList();
        }

        public SegmentType getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public String getLanguage() {
            return language;
        }

        public List<String> getTableHeaders() {
            return tableHeaders;
        }

        public List<List<String>> getTableRows() {
            return tableRows;
        }
    }

    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "(\\[[^\\]]+\\]\\(https?://[^\\s)]+\\)|https?://[^\\s<>()]+|\\*\\*[^*]+\\*\\*|`[^`]+`|\\*[^*\\n]+\\*)"
    );

    private MarkdownRenderer() {
    }

    public static List<MarkdownSegment> parseSegments(String rawText) {
        List<MarkdownSegment> segments = new ArrayList<>();
        if (rawText == null || rawText.isEmpty()) {
            return segments;
        }

        String normalized = rawText.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);

        StringBuilder textBuffer = new StringBuilder();
        StringBuilder codeBuffer = new StringBuilder();
        boolean inCodeBlock = false;
        String codeLang = "";

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    flushTextBuffer(segments, textBuffer);
                    inCodeBlock = true;
                    codeLang = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                } else {
                    segments.add(new MarkdownSegment(
                            SegmentType.CODE_BLOCK,
                            trimTrailingNewline(codeBuffer.toString()),
                            codeLang
                    ));
                    codeBuffer.setLength(0);
                    codeLang = "";
                    inCodeBlock = false;
                }
                i++;
                continue;
            }

            if (inCodeBlock) {
                codeBuffer.append(line).append('\n');
                i++;
                continue;
            }

            // Detect GFM Markdown table: header row with '|' + separator row on i+1
            if (i + 1 < lines.length && looksLikeTableRow(trimmed) && isTableSeparatorRow(lines[i + 1].trim())) {
                flushTextBuffer(segments, textBuffer);
                List<String> headers = splitTableCells(trimmed);
                List<List<String>> rows = new ArrayList<>();
                StringBuilder rawTable = new StringBuilder();
                rawTable.append(line).append('\n').append(lines[i + 1]);
                i += 2;
                while (i < lines.length && looksLikeTableRow(lines[i].trim())) {
                    String rowLine = lines[i];
                    rows.add(normalizeCellCount(splitTableCells(rowLine.trim()), headers.size()));
                    rawTable.append('\n').append(rowLine);
                    i++;
                }
                segments.add(new MarkdownSegment(
                        SegmentType.TABLE,
                        rawTable.toString(),
                        "",
                        headers,
                        rows
                ));
                continue;
            }

            textBuffer.append(line);
            if (i < lines.length - 1) {
                textBuffer.append('\n');
            }
            i++;
        }

        if (inCodeBlock && codeBuffer.length() > 0) {
            segments.add(new MarkdownSegment(
                    SegmentType.CODE_BLOCK,
                    trimTrailingNewline(codeBuffer.toString()),
                    codeLang
            ));
        }
        flushTextBuffer(segments, textBuffer);

        return segments;
    }

    private static void flushTextBuffer(List<MarkdownSegment> segments, StringBuilder textBuffer) {
        if (textBuffer.length() > 0) {
            String remaining = trimTrailingNewline(textBuffer.toString());
            if (!remaining.isEmpty()) {
                segments.add(new MarkdownSegment(SegmentType.TEXT, remaining, ""));
            }
            textBuffer.setLength(0);
        }
    }

    public static boolean looksLikeTableRow(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isEmpty()) return false;
        return trimmedLine.contains("|") && trimmedLine.replace("|", "").trim().length() > 0;
    }

    public static boolean isTableSeparatorRow(String trimmedLine) {
        if (trimmedLine == null || !trimmedLine.contains("|") && !trimmedLine.contains("-")) {
            return false;
        }
        String stripped = trimmedLine;
        if (stripped.startsWith("|")) stripped = stripped.substring(1);
        if (stripped.endsWith("|")) stripped = stripped.substring(0, stripped.length() - 1);
        String[] parts = stripped.split("\\|", -1);
        if (parts.length == 0) return false;
        boolean hasDash = false;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (!p.matches("^:?-{2,}:?$")) {
                return false;
            }
            hasDash = true;
        }
        return hasDash;
    }

    private static List<String> splitTableCells(String trimmedLine) {
        String s = trimmedLine;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] raw = s.split("\\|", -1);
        List<String> cells = new ArrayList<>(raw.length);
        for (String cell : raw) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static List<String> normalizeCellCount(List<String> cells, int targetSize) {
        List<String> normalized = new ArrayList<>(cells);
        while (normalized.size() < targetSize) {
            normalized.add("");
        }
        return normalized;
    }

    public static CharSequence formatMarkdownText(String text, int inlineCodeBgColor) {
        return formatMarkdownText(text, inlineCodeBgColor, 0xFFFF5F2B);
    }

    public static CharSequence formatMarkdownText(String text, int inlineCodeBgColor, int accentColor) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int lineStart = out.length();

            if (line.startsWith("#### ")) {
                appendInlineFormatted(out, line.substring(5), inlineCodeBgColor);
                out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.04f), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("### ")) {
                appendInlineFormatted(out, line.substring(4), inlineCodeBgColor);
                out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.08f), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("## ")) {
                appendInlineFormatted(out, line.substring(3), inlineCodeBgColor);
                out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.16f), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("# ")) {
                appendInlineFormatted(out, line.substring(2), inlineCodeBgColor);
                out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.26f), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("> ")) {
                String quoteText = trimmed.substring(2);
                appendInlineFormatted(out, quoteText, inlineCodeBgColor);
                if (out.length() > lineStart) {
                    out.setSpan(new QuoteSpan(accentColor), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (trimmed.equals(">")) {
                out.append(" ");
                out.setSpan(new QuoteSpan(accentColor), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                int indentSpaces = countLeadingSpaces(line);
                if (indentSpaces >= 2) out.append("    •  ");
                else out.append("  •  ");
                appendInlineFormatted(out, trimmed.substring(2), inlineCodeBgColor);
            } else if (trimmed.matches("^\\d+\\.\\s+.*")) {
                out.append("  ");
                appendInlineFormatted(out, trimmed, inlineCodeBgColor);
            } else if (trimmed.equals("---") || trimmed.equals("***")) {
                out.append("────────────────");
            } else {
                appendInlineFormatted(out, line, inlineCodeBgColor);
            }

            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out;
    }

    private static int countLeadingSpaces(String line) {
        int c = 0;
        while (c < line.length() && line.charAt(c) == ' ') c++;
        return c;
    }

    private static void appendInlineFormatted(
            SpannableStringBuilder out,
            String line,
            int inlineCodeBgColor
    ) {
        Matcher matcher = INLINE_PATTERN.matcher(line);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                out.append(line, lastEnd, matcher.start());
            }
            String token = matcher.group();
            if (token != null) {
                int spanStart = out.length();
                if (token.startsWith("[") && token.contains("](") && token.endsWith(")")) {
                    int mid = token.indexOf("](");
                    String label = token.substring(1, mid);
                    String url = token.substring(mid + 2, token.length() - 1);
                    out.append(label);
                    out.setSpan(new URLSpan(url), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (token.startsWith("http://") || token.startsWith("https://")) {
                    out.append(token);
                    out.setSpan(new URLSpan(token), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (token.startsWith("**") && token.endsWith("**") && token.length() > 4) {
                    out.append(token, 2, token.length() - 2);
                    out.setSpan(new StyleSpan(Typeface.BOLD), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (token.startsWith("`") && token.endsWith("`") && token.length() > 2) {
                    out.append(" ").append(token, 1, token.length() - 1).append(" ");
                    out.setSpan(new TypefaceSpan("monospace"), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(inlineCodeBgColor), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (token.startsWith("*") && token.endsWith("*") && token.length() > 2) {
                    out.append(token, 1, token.length() - 1);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    out.append(token);
                }
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < line.length()) {
            out.append(line, lastEnd, line.length());
        }
    }

    private static String trimTrailingNewline(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\n') {
            end--;
        }
        return value.substring(0, end);
    }
}
