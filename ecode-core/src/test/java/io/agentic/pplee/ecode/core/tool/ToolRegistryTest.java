package io.agentic.pplee.ecode.core.tool;

import io.agentic.pplee.ecode.core.model.ToolDefinition;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void findsToolByName() {
        var read = tool("read");
        var registry = new ToolRegistry(List.of(read, tool("grep")));

        assertThat(registry.find("read")).containsSame(read);
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void keepsDefinitionsInRegistrationOrder() {
        var registry = new ToolRegistry(List.of(tool("write"), tool("read"), tool("grep")));

        assertThat(registry.definitions()).extracting(ToolDefinition::name)
                .containsExactly("write", "read", "grep");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("read"), tool("read"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test", JSON.createObjectNode());
            }

            @Override
            public ToolKind kind() {
                return ToolKind.READ;
            }

            @Override
            public String execute(JsonNode arguments, ToolContext context) {
                return "";
            }
        };
    }
}
