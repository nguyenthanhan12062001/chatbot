package com.chatai.an.domain.model;

public enum ToolCategory {
    MEMORY("Memory"),
    FILE_SYSTEM("File System"),
    TERMINAL("Terminal"),
    TERMUX("Termux"),
    ADB_SHIZUKU("Shizuku & ADB"),
    INTERNET("Internet & Search"),
    DOWNLOAD("Downloads"),
    UTILITY("Device & Utility");

    private final String label;

    ToolCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
