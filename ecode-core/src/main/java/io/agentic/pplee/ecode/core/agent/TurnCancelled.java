package io.agentic.pplee.ecode.core.agent;

/** Internal control flow only. Never leaves AgentLoop. */
final class TurnCancelled extends RuntimeException {

    TurnCancelled() {
        // No message and no stack trace: this exception is not an error.
        super(null, null, false, false);
    }
}
