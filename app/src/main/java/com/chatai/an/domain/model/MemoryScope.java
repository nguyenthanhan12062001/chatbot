package com.chatai.an.domain.model;

public enum MemoryScope {
    GLOBAL("GLOBAL", "Global"),
    PROJECT("PROJECT", "Project");

    private final String code;
    private final String label;

    MemoryScope(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static MemoryScope fromStored(String raw) {
        if ("PROJECT".equalsIgnoreCase(raw)) return PROJECT;
        return GLOBAL;
    }
}
