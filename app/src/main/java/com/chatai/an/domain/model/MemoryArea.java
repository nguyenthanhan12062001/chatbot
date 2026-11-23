package com.chatai.an.domain.model;

public enum MemoryArea {
    CHAT("CHAT", "Chat"),
    CODE("CODE", "Code"),
    SHARED("SHARED", "Shared");

    private final String code;
    private final String label;

    MemoryArea(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static MemoryArea fromStored(String raw) {
        if ("CHAT".equalsIgnoreCase(raw)) return CHAT;
        if ("CODE".equalsIgnoreCase(raw)) return CODE;
        return SHARED;
    }
}
