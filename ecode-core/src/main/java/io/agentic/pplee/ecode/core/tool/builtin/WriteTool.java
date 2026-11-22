package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteTool implements Tool {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "write",
            "Write content to a file. Creates the file if it does not exist, overwrites if it does. Automatically creates parent directories.",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Path to the file to write (relative or absolute)"
                        },
                        "content": {
                          "type": "string",
                          "description": "Content to write to the file"
                        }
                      },
                      "required": ["path", "content"]
                    }
                    """));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EDIT;
    }

    @Override
    public String title(JsonNode arguments) {
        return "Write " + arguments.path("path").asString("");
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        String rawPath = arguments.path("path").asString("");
        String content = arguments.path("content").asString("");
        Path target = ToolPaths.resolve(rawPath, context.cwd());
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("Path is a directory: " + rawPath);
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes);
        return "Successfully wrote " + bytes.length + " bytes to " + rawPath;
    }
}
