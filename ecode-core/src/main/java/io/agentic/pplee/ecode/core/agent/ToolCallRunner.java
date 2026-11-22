package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.message.StopReason;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ToolCallRunner {

    private static final String CANCELED = "Canceled by the user";

    private final TurnContext turn;
    private final ArgumentValidator validator = new ArgumentValidator();
    private final ToolBatchExecutor executor;

    ToolCallRunner(TurnContext turn) {
        this.turn = turn;
        this.executor = new ToolBatchExecutor(
                turn.toolContext(),
                turn.cancel(),
                turn.events()::toolCallFinished);
    }

    void runAll(Message.Assistant reply) {
        List<ContentBlock.ToolCall> calls = reply.toolCalls();
        boolean truncated = reply.stopReason() == StopReason.LENGTH;
        List<ToolBatchExecutor.Task> tasks = new ArrayList<>(calls.size());
        boolean cancelled = false;

        for (ContentBlock.ToolCall call : calls) {
            try {
                tasks.add(prepare(call, truncated));
            } catch (TurnCancelled e) {
                cancelled = true;
                break;
            }
        }

        List<ToolBatchExecutor.Outcome> outcomes = cancelled
                ? alreadyDoneOutcomes(tasks)
                : executor.executeAll(tasks);

        if (recordHistory(calls, outcomes) || cancelled) {
            throw new TurnCancelled();
        }
    }

    private ToolBatchExecutor.Task prepare(ContentBlock.ToolCall call, boolean truncated) {
        if (turn.cancel().isCancelled()) {
            throw new TurnCancelled();
        }
        Optional<Tool> found = turn.tools().find(call.name());
        if (found.isEmpty()) {
            return reject(call, ToolKind.OTHER, "Unknown tool: " + call.name());
        }
        Tool tool = found.get();
        if (truncated) {
            return reject(call, tool.kind(), "Response was cut off before the call completed");
        }
        Optional<String> validationError = validator.validate(tool.definition().inputSchema(), call.arguments());
        if (validationError.isPresent()) {
            return reject(call, tool.kind(), validationError.get());
        }
        String title = tool.title(call.arguments());
        turn.events().toolCallStarted(call, tool.kind(), title);
        if (tool.needsPermission(call.arguments(), turn.toolContext())) {
            switch (turn.gate().request(new PermissionRequest(call, tool.kind(), title))) {
                case REJECT -> {
                    return finishNow(Message.ToolResult.error(call, "Permission denied by the user"));
                }
                case CANCEL -> throw new TurnCancelled();
                case ALLOW -> {
                }
            }
        }
        return new ToolBatchExecutor.Task.Ready(call, tool);
    }

    private ToolBatchExecutor.Task reject(ContentBlock.ToolCall call, ToolKind kind, String reason) {
        turn.events().toolCallStarted(call, kind, call.name());
        return finishNow(Message.ToolResult.error(call, reason));
    }

    private ToolBatchExecutor.Task finishNow(Message.ToolResult result) {
        turn.events().toolCallFinished(result);
        return new ToolBatchExecutor.Task.AlreadyDone(result);
    }

    private static List<ToolBatchExecutor.Outcome> alreadyDoneOutcomes(List<ToolBatchExecutor.Task> tasks) {
        List<ToolBatchExecutor.Outcome> outcomes = new ArrayList<>(tasks.size());
        for (ToolBatchExecutor.Task task : tasks) {
            if (task instanceof ToolBatchExecutor.Task.AlreadyDone done) {
                outcomes.add(new ToolBatchExecutor.Outcome.Finished(done.result()));
            } else {
                outcomes.add(new ToolBatchExecutor.Outcome.Cancelled());
            }
        }
        return outcomes;
    }

    private boolean recordHistory(List<ContentBlock.ToolCall> calls, List<ToolBatchExecutor.Outcome> outcomes) {
        boolean anyCancelled = false;
        for (int i = 0; i < calls.size(); i++) {
            ToolBatchExecutor.Outcome outcome = i < outcomes.size()
                    ? outcomes.get(i)
                    : new ToolBatchExecutor.Outcome.Cancelled();
            switch (outcome) {
                case ToolBatchExecutor.Outcome.Finished finished -> turn.history().append(finished.result());
                case ToolBatchExecutor.Outcome.Cancelled ignored -> {
                    anyCancelled = true;
                    turn.history().append(Message.ToolResult.error(calls.get(i), CANCELED));
                }
            }
        }
        return anyCancelled;
    }
}
