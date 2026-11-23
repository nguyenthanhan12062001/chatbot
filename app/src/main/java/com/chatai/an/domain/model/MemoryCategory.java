package com.chatai.an.domain.model;

public enum MemoryCategory {
    PROFILE("PROFILE", "Profile"),
    PREFERENCE("PREFERENCE", "Preference"),
    PROJECT("PROJECT", "Project"),
    DECISION("DECISION", "Decision"),
    FACT("FACT", "Fact");

    private final String code;
    private final String label;

    MemoryCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static MemoryCategory fromStored(String raw) {
        if (raw == null || raw.trim().isEmpty()) return FACT;
        String upper = raw.trim().toUpperCase();
        if ("USER_PREFERENCE".equals(upper) || "PREFERENCE".equals(upper)) return PREFERENCE;
        if ("PROFILE".equals(upper)) return PROFILE;
        if ("PROJECT".equals(upper) || "PROJECT_CONTEXT".equals(upper) || "TASK".equals(upper)) return PROJECT;
        if ("DECISION".equals(upper)) return DECISION;
        if ("LONG_TERM".equals(upper) || "CONTEXTUAL".equals(upper) || "FACT".equals(upper)) return FACT;
        return FACT;
    }
}
