package com.chatai.an.domain.model;

/**
 * Honest real-time status for integrations, system services, and tool capabilities.
 */
public enum IntegrationStatus {
    NOT_INSTALLED,
    NOT_CONNECTED,
    CONNECTED,
    PERMISSION_REQUIRED,
    ACTIVE,
    UNAVAILABLE,
    ERROR
}
