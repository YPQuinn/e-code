package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReadTool implements Tool {

    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "read",
            "Read the contents of a text file. Each line is prefixed with its 1-based line number as 'LINE_NUMBER: CONTENT'; strip the line number prefix when passing text to edit. Output is truncated to 2000 lines or 50KB (whichever is hit first). Use offset and limit for large files.",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Path to the file to read (relative or absolute)"
                        },
                        "offset": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "Line number to start reading from (1-indexed)"
                        },
                        "limit": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "Maximum number of lines to read"
                        }
                      },
                      "required": ["path"]
                    }
                    """));

    private final int maxLines;
    private final int maxBytes;

    public ReadTool() {
        this(OutputTruncation.DEFAULT_MAX_LINES, OutputTruncation.DEFAULT_MAX_BYTES);
    }

    ReadTool(int maxLines, int maxBytes) {
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolKind kind() {
        return ToolKind.READ;
    }

    @Override
    public String title(JsonNode arguments) {
        String path = arguments.path("path").asString("");
        Integer offset = optionalInt(arguments, "offset");
        Integer limit = optionalInt(arguments, "limit");
        if (offset != null && limit != null) {
            long end = (long) offset + limit - 1;
            return "Read " + path + " (lines " + offset + "-" + end + ")";
        }
        if (offset != null) {
            return "Read " + path + " (from line " + offset + ")";
        }
        if (limit != null) {
            return "Read " + path + " (first " + limit + " lines)";
        }
        return "Read " + path;
    }

    @Override
    public boolean needsPermission(JsonNode arguments, ToolContext context) {
        String rawPath = arguments.path("path").asString("");
        Path target = ToolPaths.resolve(rawPath, context.cwd());
        return !ToolPaths.isWithinCwd(target, context.cwd());
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        String rawPath = arguments.path("path").asString("");
        Integer offsetArg = optionalInt(arguments, "offset");
        Integer limitArg = optionalInt(arguments, "limit");
        int startLine = offsetArg == null ? 1 : offsetArg;

        Path target = ToolPaths.resolve(rawPath, context.cwd());
        validateReadableFile(target, rawPath);

        ReadWindow window = readWindow(target, startLine, limitArg);
        if (window.totalLines() == 0) {
            if (startLine > 1) {
                throw new IllegalArgumentException("Offset " + startLine + " is beyond end of file (0 lines total)");
            }
            return "";
        }
        if (startLine > window.totalLines()) {
            throw new IllegalArgumentException(
                    "Offset " + startLine + " is beyond end of file (" + window.totalLines() + " lines total)");
        }
        return formatOutput(window, startLine, limitArg, rawPath);
    }

    private void validateReadableFile(Path target, String rawPath) throws IOException {
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("File not found: " + rawPath);
        }
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("Path is a directory: " + rawPath);
        }
        if (isBinary(target)) {
            throw new IllegalArgumentException("Cannot read binary file: " + rawPath);
        }
    }

    private ReadWindow readWindow(Path target, int startLine, Integer userLimit)
            throws IOException, InterruptedException {
        List<String> lines = new ArrayList<>();
        int totalLines = 0;
        int collectedBytes = 0;
        int firstLineBytes = 0;
        boolean firstLineExceedsLimit = false;
        OutputTruncation.Reason truncatedBy = OutputTruncation.Reason.NONE;

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(target), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                totalLines++;
                if (totalLines < startLine || truncatedBy != OutputTruncation.Reason.NONE) {
                    continue;
                }
                if (userLimit != null && lines.size() >= userLimit) {
                    continue;
                }
                if (lines.size() >= maxLines) {
                    truncatedBy = OutputTruncation.Reason.LINES;
                    continue;
                }
                String formatted = totalLines + ": " + line;
                int lineBytes = OutputTruncation.utf8Length(formatted) + (lines.isEmpty() ? 0 : 1);
                if (collectedBytes + lineBytes > maxBytes) {
                    if (lines.isEmpty()) {
                        firstLineExceedsLimit = true;
                        firstLineBytes = lineBytes;
                    }
                    truncatedBy = OutputTruncation.Reason.BYTES;
                    continue;
                }
                lines.add(formatted);
                collectedBytes += lineBytes;
            }
        }

        return new ReadWindow(lines, totalLines, truncatedBy, firstLineExceedsLimit, firstLineBytes);
    }

    private String formatOutput(ReadWindow window, int startLine, Integer userLimit, String rawPath) {
        if (window.firstLineExceedsLimit()) {
            return "[Line " + startLine + " is " + OutputTruncation.formatSize(window.firstLineBytes())
                    + ", exceeds " + OutputTruncation.formatSize(maxBytes) + " limit. Use bash: sed -n '"
                    + startLine + "p' " + rawPath + " | head -c " + maxBytes + "]";
        }
        String content = String.join("\n", window.lines());
        int endLine = startLine + window.lines().size() - 1;
        int nextOffset = endLine + 1;
        if (window.truncatedBy() == OutputTruncation.Reason.LINES) {
            return content + "\n\n[Showing lines " + startLine + "-" + endLine + " of " + window.totalLines()
                    + ". Use offset=" + nextOffset + " to continue.]";
        }
        if (window.truncatedBy() == OutputTruncation.Reason.BYTES) {
            return content + "\n\n[Showing lines " + startLine + "-" + endLine + " of " + window.totalLines()
                    + " (" + OutputTruncation.formatSize(maxBytes) + " limit). Use offset=" + nextOffset
                    + " to continue.]";
        }
        if (userLimit != null && endLine < window.totalLines()) {
            int remaining = window.totalLines() - endLine;
            return content + "\n\n[" + remaining + " more lines in file. Use offset=" + nextOffset + " to continue.]";
        }
        return content;
    }

    private static boolean isBinary(Path file) throws IOException {
        byte[] buffer = new byte[BINARY_PROBE_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Integer optionalInt(JsonNode arguments, String field) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.intValue();
    }

    private record ReadWindow(
            List<String> lines,
            int totalLines,
            OutputTruncation.Reason truncatedBy,
            boolean firstLineExceedsLimit,
            int firstLineBytes) {
    }
}
