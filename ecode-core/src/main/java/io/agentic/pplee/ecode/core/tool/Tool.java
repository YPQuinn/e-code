package io.agentic.pplee.ecode.core.tool;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import tools.jackson.databind.JsonNode;

public interface Tool {

    /** Name, description and input schema that are sent to the model. */
    ToolDefinition definition();

    ToolKind kind();

    /**
     * Runs one call. {@code arguments} are already validated against the input schema.
     * Any thrown exception is turned into an error result by the agent loop.
     */
    String execute(JsonNode arguments, ToolContext context) throws Exception;
}