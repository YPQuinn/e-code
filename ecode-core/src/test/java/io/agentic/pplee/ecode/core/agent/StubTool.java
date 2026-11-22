package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

final class StubTool implements Tool {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String name;
    private ToolKind kind = ToolKind.READ;
    private JsonNode inputSchema = JSON.createObjectNode();
    private boolean needsPermission;
    private Callable<String> body = () -> "ok";
    private final AtomicInteger executions = new AtomicInteger();

    StubTool(String name) {
        this.name = name;
    }

    StubTool kind(ToolKind kind) {
        this.kind = kind;
        return this;
    }

    StubTool inputSchema(JsonNode inputSchema) {
        this.inputSchema = inputSchema;
        return this;
    }

    StubTool needingPermission() {
        this.needsPermission = true;
        return this;
    }

    StubTool returning(String text) {
        this.body = () -> text;
        return this;
    }

    StubTool doing(Callable<String> body) {
        this.body = body;
        return this;
    }

    int executions() {
        return executions.get();
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, "stub", inputSchema);
    }

    @Override
    public ToolKind kind() {
        return kind;
    }

    @Override
    public String title(JsonNode arguments) {
        return "Run " + name;
    }

    @Override
    public boolean needsPermission(JsonNode arguments, ToolContext context) {
        return needsPermission;
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        executions.incrementAndGet();
        return body.call();
    }
}
