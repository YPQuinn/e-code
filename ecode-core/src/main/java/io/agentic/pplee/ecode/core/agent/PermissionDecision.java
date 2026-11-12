package io.agentic.pplee.ecode.core.agent;

public enum PermissionDecision {
    ALLOW,
    REJECT,
    /** The user canceled the whole turn. */
    CANCEL
}
