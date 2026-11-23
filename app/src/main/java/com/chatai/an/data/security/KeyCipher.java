package com.chatai.an.data.security;

public interface KeyCipher {
    String encrypt(String plaintext);

    String decrypt(String ciphertext);

    static String maskKey(String rawKey) {
        if (rawKey == null) return "";
        String trimmed = rawKey.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() <= 8) {
            return "••••" + trimmed.substring(Math.max(0, trimmed.length() - 2));
        }
        return trimmed.substring(0, 4) + "••••" + trimmed.substring(trimmed.length() - 4);
    }
}
