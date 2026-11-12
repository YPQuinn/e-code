package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.tool.ToolKind;

public record PermissionRequest(ContentBlock.ToolCall call,
                                ToolKind kind,
                                String title) {
}
