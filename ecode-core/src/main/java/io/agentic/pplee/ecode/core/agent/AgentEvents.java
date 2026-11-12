package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.tool.ToolKind;

/** Receives what happens in one turn. The ACP adapter maps these calls to session/update. */
public interface AgentEvents {

    void textDelta(String text);

    void thinkingDelta(String text);

    void toolCallStarted(ContentBlock.ToolCall call, ToolKind kind, String title);

    void toolCallFinished(Message.ToolResult result);
}
