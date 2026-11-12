package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.Callable;

/** A read tool whose behavior is set by the test. */
final class StubTool implements Tool {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String name;
    private boolean needsPermission;
    private Callable<String> body = () -> "ok";
    private int executions;

    StubTool(String name) {
        this.name = name;
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
        return executions;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, "stub", JSON.createObjectNode());
    }

    @Override
    public ToolKind kind() {
        return ToolKind.READ;
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
        executions++;
        return body.call();
    }
}
