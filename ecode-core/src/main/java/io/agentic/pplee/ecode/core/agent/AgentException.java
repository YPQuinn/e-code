package io.agentic.pplee.ecode.core.agent;

/** The model call failed. The ACP adapter maps it to a JSON-RPC error. */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }
}
