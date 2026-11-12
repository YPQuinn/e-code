package io.agentic.pplee.ecode.core.model;

import tools.jackson.databind.JsonNode;

public record ToolDefinition(String name,
                             String description,
                             JsonNode inputSchema) {
}
