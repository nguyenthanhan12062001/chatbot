package com.chatai.an.domain.model;

/**
 * Reference source accessed or cited during an AI turn (web URL, workspace file, or tool source).
 */
public class SourceItem {
    public enum SourceType {
        WEB,
        FILE,
        TOOL
    }

    private final String title;
    private final String urlOrPath;
    private final SourceType type;
    private final String snippet;

    public SourceItem(String title, String urlOrPath, SourceType type, String snippet) {
        this.title = title != null ? title : "";
        this.urlOrPath = urlOrPath != null ? urlOrPath : "";
        this.type = type != null ? type : SourceType.WEB;
        this.snippet = snippet != null ? snippet : "";
    }

    public String getTitle() {
        return title;
    }

    public String getUrlOrPath() {
        return urlOrPath;
    }

    public SourceType getType() {
        return type;
    }

    public String getSnippet() {
        return snippet;
    }
}
