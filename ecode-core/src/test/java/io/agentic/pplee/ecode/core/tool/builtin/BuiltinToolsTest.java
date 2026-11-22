package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinToolsTest {

    @Test
    void providesAllSevenLocalToolsWithUniqueNames() {
        var registry = new ToolRegistry(BuiltinTools.local());

        assertThat(registry.definitions())
                .extracting(ToolDefinition::name)
                .containsExactly("read", "ls", "find", "grep", "write", "edit", "bash");
    }
}
