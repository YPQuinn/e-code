package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.message.StopReason;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolKind;

/** Runs the tool calls of one assistant reply, one after another. */
final class ToolCallRunner {

    private static final String CANCELED = "Canceled by the user";

    private final TurnContext turn;

    ToolCallRunner(TurnContext turn) {
        this.turn = turn;
    }

    void runAll(Message.Assistant reply) {
        boolean truncated = reply.stopReason() == StopReason.LENGTH;
        try {
            for (ContentBlock.ToolCall call : reply.toolCalls()) {
                turn.history().append(run(call, truncated));
            }
        } catch (TurnCancelled e) {
            turn.history().failUnanswered(CANCELED);
            throw e;
        }
    }

    private Message.ToolResult run(ContentBlock.ToolCall call, boolean truncated) {
        var tool = turn.tools().find(call.name());
        if (tool.isEmpty()) {
            return reject(call, "Unknown tool: " + call.name());
        }
        if (truncated) {
            return reject(call, "Response was cut off before the call completed");
        }
        return execute(call, tool.get());
    }

    private Message.ToolResult reject(ContentBlock.ToolCall call, String reason) {
        turn.events().toolCallStarted(call, ToolKind.OTHER, call.name());
        return finish(Message.ToolResult.error(call, reason));
    }

    private Message.ToolResult execute(ContentBlock.ToolCall call, Tool tool) {
        String title = tool.title(call.arguments());
        turn.events().toolCallStarted(call, tool.kind(), title);
        if (tool.needsPermission(call.arguments(), turn.toolContext())) {
            switch (turn.gate().request(new PermissionRequest(call, tool.kind(), title))) {
                case REJECT -> {
                    return finish(Message.ToolResult.error(call, "Permission denied by the user"));
                }
                case CANCEL -> throw new TurnCancelled();
                case ALLOW -> {
                }
            }
        }
        return invoke(call, tool);
    }

    private Message.ToolResult invoke(ContentBlock.ToolCall call, Tool tool) {
        try {
            String text = tool.execute(call.arguments(), turn.toolContext());
            return finish(new Message.ToolResult(call.id(), call.name(), text, false));
        } catch (Exception e) {
            if (turn.cancel().isCancelled()) {
                throw new TurnCancelled();
            }
            return finish(Message.ToolResult.error(call, String.valueOf(e.getMessage())));
        }
    }

    private Message.ToolResult finish(Message.ToolResult result) {
        turn.events().toolCallFinished(result);
        return result;
    }
}
