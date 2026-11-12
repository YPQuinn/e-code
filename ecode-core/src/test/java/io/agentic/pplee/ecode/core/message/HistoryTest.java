package io.agentic.pplee.ecode.core.message;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ContentBlock.ToolCall FIRST = new ContentBlock.ToolCall("call_1", "read", JSON.createObjectNode());
    private static final ContentBlock.ToolCall SECOND = new ContentBlock.ToolCall("call_2", "grep", JSON.createObjectNode());

    @Test
    void snapshotDoesNotChangeWhenHistoryGrows() {
        var history = new History(List.of(new Message.User("hi")));

        var snapshot = history.snapshot();
        history.append(new Message.User("again"));

        assertThat(snapshot).hasSize(1);
        assertThat(history.snapshot()).hasSize(2);
    }

    @Test
    void failsOnlyCallsWithoutResult() {
        var history = new History(List.of(
                new Message.User("hi"),
                assistant(FIRST, SECOND),
                new Message.ToolResult("call_1", "read", "content", false)));

        history.failUnanswered("Canceled");

        assertThat(history.snapshot()).last()
                .isEqualTo(new Message.ToolResult("call_2", "grep", "Canceled", true));
        assertThat(history.snapshot()).hasSize(4);
    }

    @Test
    void changesNothingWhenAllCallsHaveResults() {
        var history = new History(List.of(
                assistant(FIRST),
                new Message.ToolResult("call_1", "read", "content", false)));

        history.failUnanswered("Canceled");

        assertThat(history.snapshot()).hasSize(2);
    }

    @Test
    void changesNothingWithoutAssistantMessage() {
        var history = new History(List.of(new Message.User("hi")));

        history.failUnanswered("Canceled");

        assertThat(history.snapshot()).hasSize(1);
    }

    private static Message.Assistant assistant(ContentBlock... content) {
        return new Message.Assistant(List.of(content), StopReason.TOOL_USE, Usage.EMPTY);
    }
}
