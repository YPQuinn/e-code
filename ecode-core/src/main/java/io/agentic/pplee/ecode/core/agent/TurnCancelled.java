package io.agentic.pplee.ecode.core.agent;

final class TurnCancelled extends RuntimeException {

    TurnCancelled() {
        super(null, null, false, false);
    }
}
