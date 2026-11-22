package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class LsTool implements Tool {

    static final int DEFAULT_LIMIT = 500;

    private static final Comparator<Path> ENTRY_ORDER = Comparator
            .comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(p -> p.getFileName().toString());

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "ls",
            "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. Includes dotfiles. Output is truncated to 500 entries, 2000 lines, or 50KB (whichever is hit first).",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Directory to list (default: current directory)"
                        },
                        "limit": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "Maximum number of entries to return (default: 500)"
                        }
                      }
                    }
                    """));

    private final int defaultLimit;
    private final int maxLines;
    private final int maxBytes;

    public LsTool() {
        this(DEFAULT_LIMIT, OutputTruncation.DEFAULT_MAX_LINES, OutputTruncation.DEFAULT_MAX_BYTES);
    }

    LsTool(int defaultLimit, int maxLines, int maxBytes) {
        this.defaultLimit = defaultLimit;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolKind kind() {
        return ToolKind.SEARCH;
    }

    @Override
    public String title(JsonNode arguments) {
        return "List " + rawPathOrDot(arguments);
    }

    @Override
    public boolean needsPermission(JsonNode arguments, ToolContext context) {
        Path target = ToolPaths.resolve(rawPathOrDot(arguments), context.cwd());
        return !ToolPaths.isWithinCwd(target, context.cwd());
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        String rawPath = rawPathOrDot(arguments);
        int entryLimit = optionalInt(arguments, "limit", defaultLimit);
        Path dir = ToolPaths.resolve(rawPath, context.cwd());
        validateDirectory(dir, rawPath);

        List<String> entries = listEntries(dir);
        if (entries.isEmpty()) {
            return "(empty directory)";
        }

        String fullOutput = String.join("\n", entries);
        int effectiveMaxLines = Math.min(entryLimit, maxLines);
        OutputTruncation.Result truncation = OutputTruncation.truncateHead(fullOutput, effectiveMaxLines, maxBytes);
        if (!truncation.truncated()) {
            return truncation.content();
        }

        Path tempFile = OutputTruncation.writeTempFile("ls", fullOutput);
        return formatTruncated(truncation, effectiveMaxLines, tempFile);
    }

    private void validateDirectory(Path dir, String rawPath) {
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("Path not found: " + rawPath);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + rawPath);
        }
    }

    private List<String> listEntries(Path dir) throws IOException, InterruptedException {
        List<Path> sorted;
        try (Stream<Path> stream = Files.list(dir)) {
            sorted = stream.sorted(ENTRY_ORDER).toList();
        }
        List<String> formatted = new ArrayList<>(sorted.size());
        for (Path entry : sorted) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            String name = entry.getFileName().toString();
            formatted.add(Files.isDirectory(entry) ? name + "/" : name);
        }
        return formatted;
    }

    private String formatTruncated(OutputTruncation.Result truncation, int effectiveMaxLines, Path tempFile) {
        if (truncation.firstLineExceedsLimit()) {
            return "[Entry exceeds " + OutputTruncation.formatSize(maxBytes)
                    + " limit. Full output saved to: " + tempFile + "]";
        }
        if (truncation.reason() == OutputTruncation.Reason.BYTES) {
            return truncation.content() + "\n\n[Showing " + truncation.outputLines() + " of "
                    + truncation.totalLines() + " entries (" + OutputTruncation.formatSize(maxBytes)
                    + " limit). Full output saved to: " + tempFile + "]";
        }
        return truncation.content() + "\n\n[Showing " + truncation.outputLines() + " of "
                + truncation.totalLines() + " entries (limit " + effectiveMaxLines
                + "). Full output saved to: " + tempFile + "]";
    }

    private static String rawPathOrDot(JsonNode arguments) {
        JsonNode node = arguments.path("path");
        if (node.isMissingNode() || node.isNull()) {
            return ".";
        }
        return node.asString(".");
    }

    private static int optionalInt(JsonNode arguments, String field, int fallback) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.intValue();
    }
}
