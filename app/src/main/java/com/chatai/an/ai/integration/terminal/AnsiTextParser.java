package com.chatai.an.ai.integration.terminal;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight VT100/ANSI SGR escape sequence parser & stripper for terminal output.
 * Supports SGR foreground colors (30-37, 90-97), bold/italic/underline/reset,
 * while safely stripping non-SGR cursor/OSC control sequences.
 */
public final class AnsiTextParser {

    // Matches CSI sequences (\u001B[...<final>) or OSC sequences (\u001B]...\u0007 or \u001B\\)
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\u001B\\[[0-9;?]*[ -/]*[@-~]|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)"
    );

    private AnsiTextParser() {
    }

    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = ANSI_PATTERN.matcher(text).replaceAll("");
        return cleaned.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static void appendAnsiStyled(SpannableStringBuilder target, String rawChunk, int defaultColor) {
        if (target == null || rawChunk == null || rawChunk.isEmpty()) {
            return;
        }
        String normalized = rawChunk.replace("\r\n", "\n").replace('\r', '\n');
        Matcher matcher = ANSI_PATTERN.matcher(normalized);

        int lastIdx = 0;
        Integer activeColor = null;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;

        while (matcher.find()) {
            if (matcher.start() > lastIdx) {
                appendStyledSegment(
                        target,
                        normalized.substring(lastIdx, matcher.start()),
                        activeColor != null ? activeColor : defaultColor,
                        bold,
                        italic,
                        underline
                );
            }
            String seq = matcher.group();
            if (seq.startsWith("\u001B[") && seq.endsWith("m")) {
                String params = seq.substring(2, seq.length() - 1);
                if (params.isEmpty()) {
                    activeColor = null;
                    bold = false;
                    italic = false;
                    underline = false;
                } else {
                    String[] codes = params.split(";");
                    for (String c : codes) {
                        int code;
                        try {
                            code = c.isEmpty() ? 0 : Integer.parseInt(c);
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                        if (code == 0) {
                            activeColor = null;
                            bold = false;
                            italic = false;
                            underline = false;
                        } else if (code == 1) {
                            bold = true;
                        } else if (code == 3) {
                            italic = true;
                        } else if (code == 4) {
                            underline = true;
                        } else if (code == 22) {
                            bold = false;
                        } else if (code == 23) {
                            italic = false;
                        } else if (code == 24) {
                            underline = false;
                        } else if (code == 39) {
                            activeColor = null;
                        } else {
                            Integer mapped = mapAnsiColor(code);
                            if (mapped != null) {
                                activeColor = mapped;
                            }
                        }
                    }
                }
            }
            lastIdx = matcher.end();
        }

        if (lastIdx < normalized.length()) {
            appendStyledSegment(
                    target,
                    normalized.substring(lastIdx),
                    activeColor != null ? activeColor : defaultColor,
                    bold,
                    italic,
                    underline
            );
        }
    }

    public static void appendColored(SpannableStringBuilder target, String text, int color, boolean bold) {
        if (target == null || text == null || text.isEmpty()) return;
        appendStyledSegment(target, text, color, bold, false, false);
    }

    private static void appendStyledSegment(
            SpannableStringBuilder target,
            String text,
            int color,
            boolean bold,
            boolean italic,
            boolean underline
    ) {
        if (text.isEmpty()) return;
        int start = target.length();
        target.append(text);
        int end = target.length();
        target.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold && italic) {
            target.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (bold) {
            target.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (italic) {
            target.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (underline) {
            target.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static Integer mapAnsiColor(int code) {
        switch (code) {
            case 30: return Color.parseColor("#6E7681"); // Black / Muted gray on dark console
            case 31: return Color.parseColor("#FF7B72"); // Red
            case 32: return Color.parseColor("#3FB950"); // Green
            case 33: return Color.parseColor("#D29922"); // Yellow
            case 34: return Color.parseColor("#58A6FF"); // Blue
            case 35: return Color.parseColor("#BC8CFF"); // Magenta
            case 36: return Color.parseColor("#39C5CF"); // Cyan
            case 37: return Color.parseColor("#E6EDF3"); // White
            case 90: return Color.parseColor("#8B949E"); // Bright Black
            case 91: return Color.parseColor("#FFA198"); // Bright Red
            case 92: return Color.parseColor("#56D364"); // Bright Green
            case 93: return Color.parseColor("#E3B341"); // Bright Yellow
            case 94: return Color.parseColor("#79C0FF"); // Bright Blue
            case 95: return Color.parseColor("#D2A8FF"); // Bright Magenta
            case 96: return Color.parseColor("#56D4DD"); // Bright Cyan
            case 97: return Color.parseColor("#FFFFFF"); // Bright White
            default: return null;
        }
    }
}
