package com.chatai.an.domain.model;

/**
 * Selects which terminal backend executes built-in shell/terminal actions:
 * - APP_TERMINAL: In-app sandboxed shell (/system/bin/sh) inside ChatAI's workspace
 * - TERMUX: External Termux app via Termux RUN_COMMAND API
 */
public enum TerminalBackend {
    APP_TERMINAL("app_terminal", "App Terminal"),
    TERMUX("termux", "Termux");

    private final String id;
    private final String defaultLabel;

    TerminalBackend(String id, String defaultLabel) {
        this.id = id;
        this.defaultLabel = defaultLabel;
    }

    public String getId() {
        return id;
    }

    public String getDefaultLabel() {
        return defaultLabel;
    }

    public static TerminalBackend fromName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return APP_TERMINAL;
        }
        String norm = value.trim().toUpperCase(java.util.Locale.ROOT);
        if ("TERMUX".equals(norm)) {
            return TERMUX;
        }
        if ("APP".equals(norm) || "APP_TERMINAL".equals(norm)) {
            return APP_TERMINAL;
        }
        return APP_TERMINAL;
    }
}
