package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.tool.ToolKind;

import java.util.ArrayList;
import java.util.List;

/** Records every event as one short line, for example {@code start:call_1:READ:Run read}. */
final class RecordingEvents implements AgentEvents {

    private final List<String> log = new ArrayList<>();

    List<String> log() {
        return log;
    }

    @Override
    public void textDelta(String text) {
        log.add("text:" + text);
    }

    @Override
    public void thinkingDelta(String text) {
        log.add("thinking:" + text);
    }

    @Override
    public void toolCallStarted(ContentBlock.ToolCall call, ToolKind kind, String title) {
        log.add("start:" + call.id() + ":" + kind + ":" + title);
    }

    @Override
    public void toolCallFinished(Message.ToolResult result) {
        log.add("finish:" + result.toolCallId() + ":" + result.isError());
    }
}
