package dev.axion.core.notifications.application;

/** Whether a stream opened, or which of its limits refused it. */
public enum OpenResult {
    OPENED,
    MEMBER_LIMIT_REACHED,
    TENANT_LIMIT_REACHED
}
