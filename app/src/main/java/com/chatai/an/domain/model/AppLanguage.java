package com.chatai.an.domain.model;

public enum AppLanguage {
    ENGLISH("en", "English"),
    VIETNAMESE("vi", "Tiếng Việt"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    CHINESE("zh", "中文"),
    FRENCH("fr", "Français");

    private final String tag;
    private final String nativeName;

    AppLanguage(String tag, String nativeName) {
        this.tag = tag;
        this.nativeName = nativeName;
    }

    public String getTag() {
        return tag;
    }

    public String getNativeName() {
        return nativeName;
    }

    public static AppLanguage fromTag(String tag) {
        if (tag == null) return ENGLISH;
        for (AppLanguage lang : values()) {
            if (lang.tag.equalsIgnoreCase(tag) || tag.toLowerCase().startsWith(lang.tag)) {
                return lang;
            }
        }
        return ENGLISH;
    }
}
