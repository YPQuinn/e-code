package io.agentic.pplee.ecode.core.model;

import io.agentic.pplee.ecode.core.message.Message;

public sealed interface ModelEvent {

    record TextDelta(String text) implements ModelEvent {}

    record ThinkingDelta(String text) implements ModelEvent {}

    record ToolCallDelta(String id, String name, String argumentsJson) implements ModelEvent {}

    record Done(Message.Assistant message) implements ModelEvent {}

    record Error(String message) implements ModelEvent {}
}
