package io.agentic.pplee.ecode.core.tool;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import tools.jackson.databind.JsonNode;

public interface Tool {

    ToolDefinition definition();

    ToolKind kind();

    default String title(JsonNode arguments) {
        return definition().name();
    }

    default boolean needsPermission(JsonNode arguments, ToolContext context) {
        return true;
    }

    String execute(JsonNode arguments, ToolContext context) throws Exception;
}
