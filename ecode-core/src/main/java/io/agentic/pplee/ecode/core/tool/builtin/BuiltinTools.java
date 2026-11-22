package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.tool.Tool;

import java.util.List;

public final class BuiltinTools {

    private BuiltinTools() {
    }

    public static List<Tool> local() {
        return List.of(
                new ReadTool(),
                new LsTool(),
                new FindTool(),
                new GrepTool(),
                new WriteTool(),
                new EditTool(),
                new BashTool());
    }
}
