package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.History;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.message.StopReason;
import io.agentic.pplee.ecode.core.message.Usage;
import io.agentic.pplee.ecode.core.model.ModelEvent;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ScriptedModel model = new ScriptedModel();
    private final RecordingEvents events = new RecordingEvents();
    private final FakeCancelToken cancel = new FakeCancelToken();
    private final List<PermissionRequest> asked = new ArrayList<>();
    private final History history = new History(List.of(
            new Message.SystemPrompt("system"),
            new Message.User("hi")));

    private PermissionDecision decision = PermissionDecision.ALLOW;

    @Test
    void endsTurnWhenReplyHasNoToolCalls() {
        model.reply(assistant(StopReason.STOP, new ContentBlock.Text("done")));

        assertThat(run(TurnLimit.unlimited())).isEqualTo(TurnOutcome.END_TURN);
        assertThat(messages()).hasSize(3);
    }

    @Test
    void forwardsModelDeltasToEvents() {
        model.emit(new ModelEvent.ThinkingDelta("hm"),
                new ModelEvent.TextDelta("hel"),
                new ModelEvent.Done(assistant(StopReason.STOP, new ContentBlock.Text("hello"))));

        run(TurnLimit.unlimited());

        assertThat(events.log()).containsExactly("thinking:hm", "text:hel");
    }

    @Test
    void runsToolCallAndSendsResultBackToModel() {
        var read = new StubTool("read").returning("content");
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "read")))
                .reply(assistant(StopReason.STOP, new ContentBlock.Text("done")));

        assertThat(run(TurnLimit.unlimited(), read)).isEqualTo(TurnOutcome.END_TURN);

        assertThat(messages()).extracting(message -> message.getClass().getSimpleName())
                .containsExactly("SystemPrompt", "User", "Assistant", "ToolResult", "Assistant");
        assertThat(toolResults()).containsExactly(new Message.ToolResult("call_1", "read", "content", false));
        assertThat(model.requests().get(1).messages()).hasSize(4);
        assertThat(events.log()).containsExactly("start:call_1:READ:Run read", "finish:call_1:false");
    }

    @Test
    void failsUnknownToolAndContinues() {
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "nope")))
                .reply(assistant(StopReason.STOP));

        assertThat(run(TurnLimit.unlimited())).isEqualTo(TurnOutcome.END_TURN);

        assertThat(toolResults()).containsExactly(new Message.ToolResult("call_1", "nope", "Unknown tool: nope", true));
        assertThat(events.log()).containsExactly("start:call_1:OTHER:nope", "finish:call_1:true");
    }

    @Test
    void turnsToolExceptionIntoErrorResultAndContinues() {
        var read = new StubTool("read").doing(() -> {
            throw new IOException("disk full");
        });
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "read")))
                .reply(assistant(StopReason.STOP));

        assertThat(run(TurnLimit.unlimited(), read)).isEqualTo(TurnOutcome.END_TURN);

        assertThat(toolResults()).containsExactly(new Message.ToolResult("call_1", "read", "disk full", true));
    }

    @Test
    void doesNotAskPermissionWhenToolDoesNotNeedIt() {
        var read = new StubTool("read");
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "read")))
                .reply(assistant(StopReason.STOP));

        run(TurnLimit.unlimited(), read);

        assertThat(asked).isEmpty();
        assertThat(read.executions()).isEqualTo(1);
    }

    @Test
    void asksPermissionWithKindAndTitleWhenToolNeedsIt() {
        var write = new StubTool("write").needingPermission();
        var call = call("call_1", "write");
        model.reply(assistant(StopReason.TOOL_USE, call))
                .reply(assistant(StopReason.STOP));

        run(TurnLimit.unlimited(), write);

        assertThat(asked).containsExactly(new PermissionRequest(call, write.kind(), "Run write"));
        assertThat(write.executions()).isEqualTo(1);
    }

    @Test
    void failsOnlyTheRejectedCall() {
        var write = new StubTool("write").needingPermission();
        decision = PermissionDecision.REJECT;
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "write")))
                .reply(assistant(StopReason.STOP));

        assertThat(run(TurnLimit.unlimited(), write)).isEqualTo(TurnOutcome.END_TURN);

        assertThat(write.executions()).isZero();
        assertThat(toolResults()).containsExactly(
                new Message.ToolResult("call_1", "write", "Permission denied by the user", true));
    }

    @Test
    void cancelsTurnAndFailsPendingCallsWhenUserCancelsPermission() {
        var write = new StubTool("write").needingPermission();
        var read = new StubTool("read");
        decision = PermissionDecision.CANCEL;
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "write"), call("call_2", "read")));

        assertThat(run(TurnLimit.unlimited(), write, read)).isEqualTo(TurnOutcome.CANCELLED);

        assertThat(write.executions()).isZero();
        assertThat(read.executions()).isZero();
        assertThat(toolResults()).containsExactly(
                new Message.ToolResult("call_1", "write", "Canceled by the user", true),
                new Message.ToolResult("call_2", "read", "Canceled by the user", true));
    }

    @Test
    void cancelsTurnWhenToolFailsAfterCancel() {
        var read = new StubTool("read").doing(() -> {
            cancel.cancel();
            throw new InterruptedException();
        });
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "read")));

        assertThat(run(TurnLimit.unlimited(), read)).isEqualTo(TurnOutcome.CANCELLED);

        assertThat(toolResults()).containsExactly(new Message.ToolResult("call_1", "read", "Canceled by the user", true));
    }

    @Test
    void runsNoCallOfTruncatedReply() {
        var read = new StubTool("read");
        model.reply(assistant(StopReason.LENGTH, call("call_1", "read")))
                .reply(assistant(StopReason.STOP));

        assertThat(run(TurnLimit.unlimited(), read)).isEqualTo(TurnOutcome.END_TURN);

        assertThat(read.executions()).isZero();
        assertThat(toolResults()).singleElement()
                .satisfies(result -> assertThat(result.isError()).isTrue());
    }

    @Test
    void reportsMaxTokensWhenTruncatedReplyHasNoToolCalls() {
        model.reply(assistant(StopReason.LENGTH, new ContentBlock.Text("cut")));

        assertThat(run(TurnLimit.unlimited())).isEqualTo(TurnOutcome.MAX_TOKENS);
    }

    @Test
    void stopsAfterMaxTurns() {
        var read = new StubTool("read");
        model.reply(assistant(StopReason.TOOL_USE, call("call_1", "read")))
                .reply(assistant(StopReason.TOOL_USE, call("call_2", "read")));

        assertThat(run(new TurnLimit(2), read)).isEqualTo(TurnOutcome.MAX_TURN_REQUESTS);

        assertThat(model.requests()).hasSize(2);
        assertThat(toolResults()).hasSize(2);
    }

    @Test
    void throwsWhenModelFails() {
        model.fail("boom");

        assertThatThrownBy(() -> run(TurnLimit.unlimited()))
                .isInstanceOf(AgentException.class)
                .hasMessage("boom");
    }

    @Test
    void reportsCancelledWhenModelFailsAfterCancel() {
        cancel.cancel();
        model.fail("interrupted");

        assertThat(run(TurnLimit.unlimited())).isEqualTo(TurnOutcome.CANCELLED);
    }

    // The only place that knows how to build and start the loop.
    private TurnOutcome run(TurnLimit limit, Tool... tools) {
        PermissionGate gate = request -> {
            asked.add(request);
            return decision;
        };
        var turn = new TurnContext(history, new ToolRegistry(List.of(tools)), new ToolContext(Path.of(".")),
                events, gate, cancel);
        return new AgentLoop(model, limit).run(turn);
    }

    private List<Message> messages() {
        return history.snapshot();
    }

    private List<Message.ToolResult> toolResults() {
        return messages().stream()
                .filter(Message.ToolResult.class::isInstance)
                .map(Message.ToolResult.class::cast)
                .toList();
    }

    private static Message.Assistant assistant(StopReason stopReason, ContentBlock... content) {
        return new Message.Assistant(List.of(content), stopReason, Usage.EMPTY);
    }

    private static ContentBlock.ToolCall call(String id, String name) {
        return new ContentBlock.ToolCall(id, name, JSON.createObjectNode());
    }
}
