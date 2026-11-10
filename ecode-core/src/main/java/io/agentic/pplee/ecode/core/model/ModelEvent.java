package io.agentic.pplee.ecode.core.model;

import io.agentic.pplee.ecode.core.message.Message;

public sealed interface ModelEvent {

    record TextDelta(String text) implements ModelEvent {}

    record ThinkingDelta(String text) implements ModelEvent {}

    /** For display only. The complete tool calls arrive in {@link Done}. */
    record ToolCallDelta(String id, String name, String argumentsJson) implements ModelEvent {}

    /** Last event on success. Carries the complete message. */
    record Done(Message.Assistant message) implements ModelEvent {}

    /** Last event on failure. */
    record Error(String message) implements ModelEvent {}
}
