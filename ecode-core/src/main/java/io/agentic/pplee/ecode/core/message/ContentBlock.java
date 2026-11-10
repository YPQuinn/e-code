package io.agentic.pplee.ecode.core.message;

import java.util.Map;

public sealed interface ContentBlock {

    record Text(String text) implements ContentBlock {}

    record Thinking(String text, String signature, boolean redacted) implements ContentBlock {}

    record ToolCall(String id, String name, Map<String, Object> arguments) implements ContentBlock {}
}
