package io.agentic.pplee.ecode.core.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ArgumentValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArgumentValidator validator = new ArgumentValidator();

    private static final JsonNode PATH_SCHEMA = JSON.readTree("""
            {
              "type": "object",
              "properties": {
                "path": { "type": "string" },
                "limit": { "type": "integer", "minimum": 1 }
              },
              "required": ["path"],
              "additionalProperties": false
            }
            """);

    @Test
    void acceptsValidArguments() {
        JsonNode args = JSON.readTree("""
                { "path": "src/Main.java", "limit": 100 }
                """);

        assertThat(validator.validate(PATH_SCHEMA, args)).isEmpty();
    }

    @Test
    void rejectsMissingRequiredField() {
        JsonNode args = JSON.readTree("""
                { "limit": 10 }
                """);

        assertThat(validator.validate(PATH_SCHEMA, args))
                .isPresent()
                .get()
                .asString()
                .contains("Invalid arguments");
    }

    @Test
    void rejectsWrongFieldType() {
        JsonNode args = JSON.readTree("""
                { "path": "src/Main.java", "limit": "ten" }
                """);

        assertThat(validator.validate(PATH_SCHEMA, args))
                .isPresent()
                .get()
                .asString()
                .contains("Invalid arguments");
    }

    @Test
    void acceptsAnyObjectForEmptyOrNullSchema() {
        JsonNode args = JSON.readTree("""
                { "anything": true }
                """);

        assertThat(validator.validate(JSON.createObjectNode(), args)).isEmpty();
        assertThat(validator.validate(null, args)).isEmpty();
    }

    @Test
    void rejectsNullArguments() {
        assertThat(validator.validate(PATH_SCHEMA, null)).isPresent();
        assertThat(validator.validate(PATH_SCHEMA, JSON.nullNode())).isPresent();
    }
}
