package io.agentic.pplee.ecode.core.message;

import java.util.List;

public sealed interface Message {

    record SystemPrompt(String text) implements Message {}

    record User(String text) implements Message {}

    record Assistant(List<ContentBlock> content, StopReason stopReason, Usage usage) implements Message {

        public Assistant {
            content = List.copyOf(content);
        }

        public List<ContentBlock.ToolCall> toolCalls() {
            // in the order of occurrence
            return content.stream()
                    .<ContentBlock.ToolCall>mapMulti((block, sink) -> {
                        if (block instanceof ContentBlock.ToolCall call) {
                            sink.accept(call);
                        }
                    })
                    .toList();
        }
    }

    record ToolResult(String toolCallId, String toolName, String text, boolean isError) implements Message {

        public static ToolResult error(ContentBlock.ToolCall call, String text) {
            return new ToolResult(call.id(), call.name(), text, true);
        }
    }
}
