package io.agentic.pplee.ecode.core.agent;

public interface PermissionGate {

    PermissionDecision request(PermissionRequest request);
}
