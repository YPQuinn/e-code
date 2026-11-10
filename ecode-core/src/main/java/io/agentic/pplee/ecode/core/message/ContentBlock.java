package io.agentic.pplee.ecode.core.message;

import tools.jackson.databind.JsonNode;

public sealed interface ContentBlock {

    record Text(String text) implements ContentBlock {}

    record Thinking(String text, String signature, boolean redacted) implements ContentBlock {}

    record ToolCall(String id, String name, JsonNode arguments) implements ContentBlock {}
}
