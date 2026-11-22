package io.agentic.pplee.ecode.core.tool;

import io.agentic.pplee.ecode.core.model.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        for (Tool tool : tools) {
            String name = tool.definition().name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + name);
            }
        }
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<ToolDefinition> definitions() {
        return byName.values().stream().map(Tool::definition).toList();
    }
}
