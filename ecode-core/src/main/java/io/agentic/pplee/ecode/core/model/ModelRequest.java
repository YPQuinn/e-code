package io.agentic.pplee.ecode.core.model;

import io.agentic.pplee.ecode.core.message.Message;

import java.util.List;

public record ModelRequest(List<Message> messages, List<ToolDefinition> tools) {
}
