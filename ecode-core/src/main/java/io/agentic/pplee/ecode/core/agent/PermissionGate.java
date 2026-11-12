package io.agentic.pplee.ecode.core.agent;


/** Asks the user whether one tool call may run. */
public interface PermissionGate {

    PermissionDecision request(PermissionRequest request);
}
