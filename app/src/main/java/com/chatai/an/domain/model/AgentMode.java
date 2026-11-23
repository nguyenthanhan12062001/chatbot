package com.chatai.an.domain.model;

public enum AgentMode {
    CHAT("Chat Agent", "Conversational AI with Memory, Internet & Tools"),
    CODING("Coding Agent", "Project-aware developer agent with File, Git & Termux tools");

    private final String title;
    private final String description;

    AgentMode(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public static AgentMode fromName(String raw) {
        if (raw == null) return CHAT;
        for (AgentMode m : values()) {
            if (m.name().equalsIgnoreCase(raw.trim())) {
                return m;
            }
        }
        return CHAT;
    }
}
