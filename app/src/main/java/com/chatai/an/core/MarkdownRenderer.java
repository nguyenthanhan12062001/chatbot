package com.chatai.an.core;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured Markdown, LaTeX, Table, Image, and Code Block parser & formatter for ChatAI messages.
 */
public final class MarkdownRenderer {

    public enum SegmentType {
        TEXT,
        CODE_BLOCK,
        TABLE,
        LATEX_BLOCK,
        IMAGE
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

        public String getImageAlt() {
            return language;
        }

        public List<String> getTableHeaders() {
            return tableHeaders;
        }

        public List<List<String>> getTableRows() {
            return tableRows;
        }
    }

    private static final Pattern IMAGE_LINE_PATTERN = Pattern.compile(
            "^!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)$"
    );

    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "("
                    + "\\[[^\\]]+\\]\\((?:https?://|file://)[^\\s)]+\\)"
                    + "|https?://[^\\s<>()]+"
                    + "|\\*\\*[^*]+\\*\\*"
                    + "|`[^`]+`"
                    + "|\\\\\\([^\\n]+?\\\\\\)"
                    + "|\\$(?!\\s)[^\\$\\n]+?(?<!\\s)\\$"
                    + "|\\*[^*\\n]+\\*"
                    + ")"
    );

    private static final Map<String, String> LATEX_SYMBOLS = new LinkedHashMap<>();

    static {
        LATEX_SYMBOLS.put("\\implies", "⟹");
        LATEX_SYMBOLS.put("\\iff", "⟺");
        LATEX_SYMBOLS.put("\\xrightarrow", "→");
        LATEX_SYMBOLS.put("\\rightarrow", "→");
        LATEX_SYMBOLS.put("\\leftarrow", "←");
        LATEX_SYMBOLS.put("\\leftrightarrow", "↔");
        LATEX_SYMBOLS.put("\\Rightarrow", "⇒");
        LATEX_SYMBOLS.put("\\Leftarrow", "⇐");
        LATEX_SYMBOLS.put("\\Leftrightarrow", "⇔");
        LATEX_SYMBOLS.put("\\to", "→");
        LATEX_SYMBOLS.put("\\mapsto", "↦");
        LATEX_SYMBOLS.put("\\approx", "≈");
        LATEX_SYMBOLS.put("\\equiv", "≡");
        LATEX_SYMBOLS.put("\\neq", "≠");
        LATEX_SYMBOLS.put("\\ne", "≠");
        LATEX_SYMBOLS.put("\\leq", "≤");
        LATEX_SYMBOLS.put("\\le", "≤");
        LATEX_SYMBOLS.put("\\geq", "≥");
        LATEX_SYMBOLS.put("\\ge", "≥");
        LATEX_SYMBOLS.put("\\ll", "≪");
        LATEX_SYMBOLS.put("\\gg", "≫");
        LATEX_SYMBOLS.put("\\pm", "±");
        LATEX_SYMBOLS.put("\\mp", "∓");
        LATEX_SYMBOLS.put("\\times", "×");
        LATEX_SYMBOLS.put("\\cdot", "·");
        LATEX_SYMBOLS.put("\\div", "÷");
        LATEX_SYMBOLS.put("\\infty", "∞");
        LATEX_SYMBOLS.put("\\partial", "∂");
        LATEX_SYMBOLS.put("\\nabla", "∇");
        LATEX_SYMBOLS.put("\\sum", "∑");
        LATEX_SYMBOLS.put("\\prod", "∏");
        LATEX_SYMBOLS.put("\\oint", "∮");
        LATEX_SYMBOLS.put("\\int", "∫");
        LATEX_SYMBOLS.put("\\forall", "∀");
        LATEX_SYMBOLS.put("\\exists", "∃");
        LATEX_SYMBOLS.put("\\notin", "∉");
        LATEX_SYMBOLS.put("\\in", "∈");
        LATEX_SYMBOLS.put("\\subseteq", "⊆");
        LATEX_SYMBOLS.put("\\subset", "⊂");
        LATEX_SYMBOLS.put("\\supseteq", "⊇");
        LATEX_SYMBOLS.put("\\supset", "⊃");
        LATEX_SYMBOLS.put("\\cup", "∪");
        LATEX_SYMBOLS.put("\\cap", "∩");
        LATEX_SYMBOLS.put("\\emptyset", "∅");
        LATEX_SYMBOLS.put("\\varnothing", "∅");
        LATEX_SYMBOLS.put("\\therefore", "∴");
        LATEX_SYMBOLS.put("\\because", "∵");
        LATEX_SYMBOLS.put("\\propto", "∝");
        LATEX_SYMBOLS.put("\\sim", "∼");
        LATEX_SYMBOLS.put("\\simeq", "≃");
        LATEX_SYMBOLS.put("\\cong", "≅");
        LATEX_SYMBOLS.put("\\angle", "∠");
        LATEX_SYMBOLS.put("\\perp", "⊥");
        LATEX_SYMBOLS.put("\\parallel", "∥");
        LATEX_SYMBOLS.put("\\degree", "°");
        LATEX_SYMBOLS.put("\\circ", "°");
        LATEX_SYMBOLS.put("\\cdots", "⋯");
        LATEX_SYMBOLS.put("\\ldots", "…");
        LATEX_SYMBOLS.put("\\dots", "…");
        LATEX_SYMBOLS.put("\\varepsilon", "ε");
        LATEX_SYMBOLS.put("\\varphi", "φ");
        LATEX_SYMBOLS.put("\\alpha", "α");
        LATEX_SYMBOLS.put("\\beta", "β");
        LATEX_SYMBOLS.put("\\gamma", "γ");
        LATEX_SYMBOLS.put("\\delta", "δ");
        LATEX_SYMBOLS.put("\\epsilon", "ε");
        LATEX_SYMBOLS.put("\\zeta", "ζ");
        LATEX_SYMBOLS.put("\\eta", "η");
        LATEX_SYMBOLS.put("\\theta", "θ");
        LATEX_SYMBOLS.put("\\iota", "ι");
        LATEX_SYMBOLS.put("\\kappa", "κ");
        LATEX_SYMBOLS.put("\\lambda", "λ");
        LATEX_SYMBOLS.put("\\mu", "μ");
        LATEX_SYMBOLS.put("\\nu", "ν");
        LATEX_SYMBOLS.put("\\xi", "ξ");
        LATEX_SYMBOLS.put("\\pi", "π");
        LATEX_SYMBOLS.put("\\rho", "ρ");
        LATEX_SYMBOLS.put("\\sigma", "σ");
        LATEX_SYMBOLS.put("\\tau", "τ");
        LATEX_SYMBOLS.put("\\upsilon", "υ");
        LATEX_SYMBOLS.put("\\phi", "φ");
        LATEX_SYMBOLS.put("\\chi", "χ");
        LATEX_SYMBOLS.put("\\psi", "ψ");
        LATEX_SYMBOLS.put("\\omega", "ω");
        LATEX_SYMBOLS.put("\\Gamma", "Γ");
        LATEX_SYMBOLS.put("\\Delta", "Δ");
        LATEX_SYMBOLS.put("\\Theta", "Θ");
        LATEX_SYMBOLS.put("\\Lambda", "Λ");
        LATEX_SYMBOLS.put("\\Xi", "Ξ");
        LATEX_SYMBOLS.put("\\Pi", "Π");
        LATEX_SYMBOLS.put("\\Sigma", "Σ");
        LATEX_SYMBOLS.put("\\Phi", "Φ");
        LATEX_SYMBOLS.put("\\Psi", "Ψ");
        LATEX_SYMBOLS.put("\\Omega", "Ω");
        LATEX_SYMBOLS.put("\\mathbb{R}", "ℝ");
        LATEX_SYMBOLS.put("\\mathbb{N}", "ℕ");
        LATEX_SYMBOLS.put("\\mathbb{Z}", "ℤ");
        LATEX_SYMBOLS.put("\\mathbb{Q}", "ℚ");
        LATEX_SYMBOLS.put("\\mathbb{C}", "ℂ");
        LATEX_SYMBOLS.put("\\left(", "(");
        LATEX_SYMBOLS.put("\\right)", ")");
        LATEX_SYMBOLS.put("\\left[", "[");
        LATEX_SYMBOLS.put("\\right]", "]");
        LATEX_SYMBOLS.put("\\left\\{", "{");
        LATEX_SYMBOLS.put("\\right\\}", "}");
        LATEX_SYMBOLS.put("\\left|", "|");
        LATEX_SYMBOLS.put("\\right|", "|");
        LATEX_SYMBOLS.put("\\left.", "");
        LATEX_SYMBOLS.put("\\right.", "");
        LATEX_SYMBOLS.put("\\{", "{");
        LATEX_SYMBOLS.put("\\}", "}");
        LATEX_SYMBOLS.put("\\quad", "  ");
        LATEX_SYMBOLS.put("\\qquad", "    ");
        LATEX_SYMBOLS.put("\\,", " ");
        LATEX_SYMBOLS.put("\\;", " ");
        LATEX_SYMBOLS.put("\\!", "");
    }

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
        StringBuilder latexBuffer = new StringBuilder();
        boolean inCodeBlock = false;
        boolean inLatexDollarBlock = false;
        boolean inLatexBracketBlock = false;
        String codeLang = "";

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (inLatexDollarBlock) {
                if (trimmed.equals("$$")) {
                    segments.add(new MarkdownSegment(
                            SegmentType.LATEX_BLOCK,
                            trimTrailingNewline(latexBuffer.toString()),
                            "latex"
                    ));
                    latexBuffer.setLength(0);
                    inLatexDollarBlock = false;
                } else if (trimmed.endsWith("$$") && trimmed.length() > 2) {
                    latexBuffer.append(line, 0, line.lastIndexOf("$$"));
                    segments.add(new MarkdownSegment(
                            SegmentType.LATEX_BLOCK,
                            trimTrailingNewline(latexBuffer.toString()).trim(),
                            "latex"
                    ));
                    latexBuffer.setLength(0);
                    inLatexDollarBlock = false;
                } else {
                    latexBuffer.append(line).append('\n');
                }
                i++;
                continue;
            }

            if (inLatexBracketBlock) {
                if (trimmed.equals("\\]")) {
                    segments.add(new MarkdownSegment(
                            SegmentType.LATEX_BLOCK,
                            trimTrailingNewline(latexBuffer.toString()),
                            "latex"
                    ));
                    latexBuffer.setLength(0);
                    inLatexBracketBlock = false;
                } else if (trimmed.endsWith("\\]") && trimmed.length() > 2) {
                    latexBuffer.append(line, 0, line.lastIndexOf("\\]"));
                    segments.add(new MarkdownSegment(
                            SegmentType.LATEX_BLOCK,
                            trimTrailingNewline(latexBuffer.toString()).trim(),
                            "latex"
                    ));
                    latexBuffer.setLength(0);
                    inLatexBracketBlock = false;
                } else {
                    latexBuffer.append(line).append('\n');
                }
                i++;
                continue;
            }

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

            // Detect block LaTeX $$ ... $$
            if (trimmed.startsWith("$$")) {
                flushTextBuffer(segments, textBuffer);
                if (trimmed.length() > 4 && trimmed.endsWith("$$")) {
                    String expr = trimmed.substring(2, trimmed.length() - 2).trim();
                    segments.add(new MarkdownSegment(SegmentType.LATEX_BLOCK, expr, "latex"));
                } else {
                    inLatexDollarBlock = true;
                    String rest = trimmed.substring(2).trim();
                    if (!rest.isEmpty()) {
                        latexBuffer.append(rest).append('\n');
                    }
                }
                i++;
                continue;
            }

            // Detect block LaTeX \[ ... \]
            if (trimmed.startsWith("\\[")) {
                flushTextBuffer(segments, textBuffer);
                if (trimmed.length() > 4 && trimmed.endsWith("\\]")) {
                    String expr = trimmed.substring(2, trimmed.length() - 2).trim();
                    segments.add(new MarkdownSegment(SegmentType.LATEX_BLOCK, expr, "latex"));
                } else {
                    inLatexBracketBlock = true;
                    String rest = trimmed.substring(2).trim();
                    if (!rest.isEmpty()) {
                        latexBuffer.append(rest).append('\n');
                    }
                }
                i++;
                continue;
            }

            // Detect standalone Markdown image ![alt](url)
            Matcher imgMatcher = IMAGE_LINE_PATTERN.matcher(trimmed);
            if (imgMatcher.matches()) {
                flushTextBuffer(segments, textBuffer);
                String alt = imgMatcher.group(1) != null ? imgMatcher.group(1).trim() : "";
                String src = imgMatcher.group(2) != null ? imgMatcher.group(2).trim() : "";
                segments.add(new MarkdownSegment(SegmentType.IMAGE, src, alt));
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
        if ((inLatexDollarBlock || inLatexBracketBlock) && latexBuffer.length() > 0) {
            segments.add(new MarkdownSegment(
                    SegmentType.LATEX_BLOCK,
                    trimTrailingNewline(latexBuffer.toString()),
                    "latex"
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
        if (trimmedLine == null || (!trimmedLine.contains("|") && !trimmedLine.contains("-"))) {
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
                } else if (token.startsWith("\\(") && token.endsWith("\\)") && token.length() > 4) {
                    String expr = token.substring(2, token.length() - 2).trim();
                    CharSequence renderedMath = formatLatexExpression(expr);
                    out.append(renderedMath);
                    out.setSpan(new TypefaceSpan("serif"), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (token.startsWith("$") && token.endsWith("$") && token.length() > 2) {
                    String inner = token.substring(1, token.length() - 1).trim();
                    if (looksLikeInlineMath(inner)) {
                        CharSequence renderedMath = formatLatexExpression(inner);
                        out.append(renderedMath);
                        out.setSpan(new TypefaceSpan("serif"), spanStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        out.append(token);
                    }
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

    static boolean looksLikeInlineMath(String inner) {
        if (inner == null || inner.isEmpty()) return false;
        // Avoid matching currency ranges like "$5 to $10" -> inner="5 to "
        if (inner.matches("^[\\d.,\\s]+(?:to|and|or|USD|EUR|VND)?[\\d.,\\s]*$")) {
            return false;
        }
        if (inner.contains("\\") || inner.contains("^") || inner.contains("_")
                || inner.contains("=") || inner.contains("+") || inner.contains("<")
                || inner.contains(">") || inner.contains("/")) {
            return true;
        }
        // Single variable like $x$ or $n$ or $f(x)$
        return inner.matches("^[A-Za-z](?:\\([A-Za-z0-9,\\s]+\\))?$");
    }

    /**
     * Pure-Java LaTeX macro -> readable Unicode math text (safe for non-Robolectric JVM tests).
     */
    public static String formatLatexPlainText(String rawTex) {
        if (rawTex == null || rawTex.isEmpty()) return "";
        return preprocessLatexMacros(rawTex.trim());
    }

    /**
     * Converts a LaTeX math expression into a clean Unicode + Superscript/Subscript Spannable.
     */
    public static CharSequence formatLatexExpression(String rawTex) {
        if (rawTex == null || rawTex.isEmpty()) return "";
        String normalized = preprocessLatexMacros(rawTex.trim());
        SpannableStringBuilder out = new SpannableStringBuilder();
        appendFormattedLatex(normalized, 0, normalized.length(), out);
        return out;
    }

    private static String preprocessLatexMacros(String tex) {
        String s = tex;
        s = replaceFrac(s);
        s = replaceSqrt(s);
        s = replaceTextCommands(s);
        for (Map.Entry<String, String> e : LATEX_SYMBOLS.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        // Remove stray \begin{...} / \end{...} or \\ linebreaks in align blocks
        s = s.replaceAll("\\\\begin\\{[^}]+\\}", "")
                .replaceAll("\\\\end\\{[^}]+\\}", "")
                .replace("\\\\", "\n")
                .replace("&", " ");
        return s.trim();
    }

    private static String replaceFrac(String input) {
        String s = input;
        int guard = 0;
        while (guard++ < 32) {
            int idx = s.indexOf("\\frac");
            if (idx < 0) idx = s.indexOf("\\dfrac");
            if (idx < 0) idx = s.indexOf("\\tfrac");
            if (idx < 0) break;

            int cmdLen = s.startsWith("\\dfrac", idx) || s.startsWith("\\tfrac", idx) ? 6 : 5;
            int[] numSpan = extractBraceGroup(s, idx + cmdLen);
            if (numSpan == null) break;
            int[] denSpan = extractBraceGroup(s, numSpan[1]);
            if (denSpan == null) break;

            String num = s.substring(numSpan[0] + 1, numSpan[1] - 1).trim();
            String den = s.substring(denSpan[0] + 1, denSpan[1] - 1).trim();
            String numFmt = isSimpleToken(num) ? num : "(" + num + ")";
            String denFmt = isSimpleToken(den) ? den : "(" + den + ")";
            s = s.substring(0, idx) + numFmt + "/" + denFmt + s.substring(denSpan[1]);
        }
        return s;
    }

    private static String replaceSqrt(String input) {
        String s = input;
        int guard = 0;
        while (guard++ < 32) {
            int idx = s.indexOf("\\sqrt");
            if (idx < 0) break;
            int pos = idx + 5;
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
            String rootPrefix = "√";
            if (pos < s.length() && s.charAt(pos) == '[') {
                int closeBracket = s.indexOf(']', pos + 1);
                if (closeBracket > pos) {
                    String n = s.substring(pos + 1, closeBracket).trim();
                    rootPrefix = "^" + (n.length() == 1 ? n : "{" + n + "}") + "√";
                    pos = closeBracket + 1;
                }
            }
            int[] argSpan = extractBraceGroup(s, pos);
            if (argSpan == null) break;
            String body = s.substring(argSpan[0] + 1, argSpan[1] - 1).trim();
            s = s.substring(0, idx) + rootPrefix + "(" + body + ")" + s.substring(argSpan[1]);
        }
        return s;
    }

    private static String replaceTextCommands(String input) {
        String s = input;
        String[] cmds = {"\\text", "\\mathrm", "\\mathbf", "\\mathit", "\\mathcal", "\\operatorname"};
        for (String cmd : cmds) {
            int guard = 0;
            while (guard++ < 32) {
                int idx = s.indexOf(cmd);
                if (idx < 0) break;
                int[] span = extractBraceGroup(s, idx + cmd.length());
                if (span == null) break;
                String inner = s.substring(span[0] + 1, span[1] - 1);
                s = s.substring(0, idx) + inner + s.substring(span[1]);
            }
        }
        return s;
    }

    private static boolean isSimpleToken(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '.') {
                return false;
            }
        }
        return true;
    }

    private static int[] extractBraceGroup(String s, int startFrom) {
        int i = startFrom;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '{') return null;
        int depth = 0;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new int[]{i, j + 1};
                }
            }
        }
        return null;
    }

    private static void appendFormattedLatex(String s, int start, int end, SpannableStringBuilder out) {
        int i = start;
        while (i < end) {
            char c = s.charAt(i);
            if ((c == '^' || c == '_') && i + 1 < end) {
                boolean isSuper = (c == '^');
                int scriptStart = out.length();
                int nextPos = i + 1;
                while (nextPos < end && s.charAt(nextPos) == ' ') nextPos++;
                if (nextPos < end && s.charAt(nextPos) == '{') {
                    int[] grp = extractBraceGroup(s, nextPos);
                    if (grp != null && grp[1] <= end) {
                        appendFormattedLatex(s, grp[0] + 1, grp[1] - 1, out);
                        i = grp[1];
                    } else {
                        out.append(s.charAt(nextPos));
                        i = nextPos + 1;
                    }
                } else if (nextPos < end) {
                    out.append(s.charAt(nextPos));
                    i = nextPos + 1;
                } else {
                    i++;
                }
                int scriptEnd = out.length();
                if (scriptEnd > scriptStart) {
                    out.setSpan(isSuper ? new SuperscriptSpan() : new SubscriptSpan(),
                            scriptStart, scriptEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.75f),
                            scriptStart, scriptEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }
            out.append(c);
            i++;
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
