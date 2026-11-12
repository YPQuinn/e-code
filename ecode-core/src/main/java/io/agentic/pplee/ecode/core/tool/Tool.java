package io.agentic.pplee.ecode.core.tool;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import tools.jackson.databind.JsonNode;

public interface Tool {

    /** Name, description and input schema that are sent to the model. */
    ToolDefinition definition();

    ToolKind kind();

    /** One-line description of this call for the user, for example "Read src/Foo.java". */
    default String title(JsonNode arguments) {
        return definition().name();
    }

    /**
     * Tells whether the user must approve this call before it runs.
     * The default is {@code true}; read-only tools override it to skip approval inside the session cwd.
     */
    default boolean needsPermission(JsonNode arguments, ToolContext context) {
        return true;
    }

    /**
     * Runs one call. {@code arguments} are already validated against the input schema.
     * Any thrown exception is turned into an error result by the agent loop.
     */
    String execute(JsonNode arguments, ToolContext context) throws Exception;
}
