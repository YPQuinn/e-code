package io.agentic.pplee.ecode.core.agent;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

final class ArgumentValidator {

    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final Map<JsonNode, Schema> cache = new ConcurrentHashMap<>();

    Optional<String> validate(JsonNode inputSchema, JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return Optional.of("Invalid arguments: arguments must not be null");
        }
        if (inputSchema == null || inputSchema.isEmpty()) {
            return Optional.empty();
        }
        Schema schema = cache.computeIfAbsent(inputSchema,
                node -> REGISTRY.getSchema(node.toString(), InputFormat.JSON));
        List<Error> errors = schema.validate(arguments.toString(), InputFormat.JSON);
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        String details = errors.stream()
                .map(Error::getMessage)
                .collect(Collectors.joining("; "));
        return Optional.of("Invalid arguments: " + details);
    }
}
