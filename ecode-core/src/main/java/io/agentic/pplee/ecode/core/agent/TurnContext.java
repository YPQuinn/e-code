package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.History;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolRegistry;

public record TurnContext(History history,
                          ToolRegistry tools,
                          ToolContext toolContext,
                          AgentEvents events,
                          PermissionGate gate,
                          CancelToken cancel) {
}
