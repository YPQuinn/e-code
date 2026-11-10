package io.agentic.pplee.ecode.core.message;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantTest {

    private static final ContentBlock.ToolCall READ = new ContentBlock.ToolCall("call_1", "read", Map.of("path", "a.txt"));
    private static final ContentBlock.ToolCall GREP = new ContentBlock.ToolCall("call_2", "grep", Map.of("pattern", "foo"));

    @Test
    void toolCallsKeepsOrderOfOccurrence() {
        var message = assistant(
                new ContentBlock.Text("Reading the file first"),
                READ,
                new ContentBlock.Text("Then searching"),
                GREP);

        assertThat(message.toolCalls()).containsExactly(READ, GREP);
    }

    @Test
    void toolCallsSkipsThinkingAndText() {
        var message = assistant(
                new ContentBlock.Thinking("thinking", "sig", false),
                READ,
                new ContentBlock.Thinking("", "encrypted", true));

        assertThat(message.toolCalls()).containsExactly(READ);
    }

    @Test
    void toolCallsIsEmptyWithoutToolCall() {
        var message = assistant(new ContentBlock.Text("done"));

        assertThat(message.toolCalls()).isEmpty();
    }

    @Test
    void toolCallsIsEmptyWithoutContent() {
        assertThat(assistant().toolCalls()).isEmpty();
    }

    private static Message.Assistant assistant(ContentBlock... blocks) {
        return new Message.Assistant(List.of(blocks), StopReason.STOP, Usage.EMPTY);
    }
}
