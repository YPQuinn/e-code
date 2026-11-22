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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EditTool implements Tool {

    private static final String UTF8_BOM = "\uFEFF";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "edit",
            "Edit a single file using exact text replacement. Every edits[].oldText must uniquely match a non-overlapping region of the original file. Never include the 'LINE_NUMBER: ' prefix from read output in oldText or newText. Merge overlapping or adjacent changes into one edit.",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Path to the file to edit (relative or absolute)"
                        },
                        "edits": {
                          "type": "array",
                          "minItems": 1,
                          "description": "One or more targeted replacements matched against the original file",
                          "items": {
                            "type": "object",
                            "properties": {
                              "oldText": {
                                "type": "string",
                                "minLength": 1,
                                "description": "Exact text to replace; must be unique in the original file"
                              },
                              "newText": {
                                "type": "string",
                                "description": "Replacement text"
                              }
                            },
                            "required": ["oldText", "newText"]
                          }
                        }
                      },
                      "required": ["path", "edits"]
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
        String path = arguments.path("path").asString("");
        int count = arguments.path("edits").size();
        return count > 1 ? "Edit " + path + " (" + count + " edits)" : "Edit " + path;
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        String rawPath = arguments.path("path").asString("");
        List<Edit> edits = parseEdits(arguments.path("edits"));
        Path target = ToolPaths.resolve(rawPath, context.cwd());
        validateFile(target, rawPath);

        String rawContent = Files.readString(target, StandardCharsets.UTF_8);
        boolean hasBom = rawContent.startsWith(UTF8_BOM);
        String body = hasBom ? rawContent.substring(1) : rawContent;
        String lineEnding = body.contains("\r\n") ? "\r\n" : "\n";
        String normalized = normalizeToLf(body);

        List<Match> matches = matchAll(normalized, edits, rawPath);
        matches.sort(Comparator.comparingInt(Match::start));
        checkDisjoint(matches, rawPath);

        String updated = applyMatches(normalized, matches);
        if (updated.equals(normalized)) {
            throw new IllegalArgumentException(
                    "No changes made to " + rawPath + ". The replacements produced identical content.");
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        String restored = lineEnding.equals("\r\n") ? updated.replace("\n", "\r\n") : updated;
        String finalContent = hasBom ? UTF8_BOM + restored : restored;
        Files.writeString(target, finalContent, StandardCharsets.UTF_8);
        return "Successfully replaced " + edits.size() + " block(s) in " + rawPath;
    }

    private static List<Edit> parseEdits(JsonNode editsNode) {
        List<Edit> edits = new ArrayList<>(editsNode.size());
        for (JsonNode item : editsNode) {
            edits.add(new Edit(
                    item.path("oldText").asString(""),
                    item.path("newText").asString("")));
        }
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }
        return edits;
    }

    private static void validateFile(Path target, String rawPath) {
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("File not found: " + rawPath);
        }
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("Path is a directory: " + rawPath);
        }
    }

    private static List<Match> matchAll(String content, List<Edit> edits, String rawPath) {
        List<Match> matches = new ArrayList<>(edits.size());
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            String oldText = normalizeToLf(edit.oldText());
            String newText = normalizeToLf(edit.newText());
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException(label(i, edits.size()) + ".oldText must not be empty");
            }
            int first = content.indexOf(oldText);
            if (first == -1) {
                throw new IllegalArgumentException(
                        "Could not find " + label(i, edits.size()) + " in " + rawPath
                                + ". The oldText must match exactly including whitespace and newlines.");
            }
            int occurrences = countOccurrences(content, oldText);
            if (occurrences > 1) {
                throw new IllegalArgumentException(
                        "Found " + occurrences + " occurrences of " + label(i, edits.size()) + " in " + rawPath
                                + ". Provide more surrounding context to make it unique.");
            }
            matches.add(new Match(i, first, first + oldText.length(), newText));
        }
        return matches;
    }

    private static int countOccurrences(String content, String target) {
        int count = 0;
        int from = 0;
        while ((from = content.indexOf(target, from)) != -1) {
            count++;
            from += 1;
        }
        return count;
    }

    private static void checkDisjoint(List<Match> sortedMatches, String rawPath) {
        for (int i = 1; i < sortedMatches.size(); i++) {
            Match previous = sortedMatches.get(i - 1);
            Match current = sortedMatches.get(i);
            if (previous.end() > current.start()) {
                throw new IllegalArgumentException(
                        "edits[" + previous.editIndex() + "] and edits[" + current.editIndex()
                                + "] overlap in " + rawPath + ". Merge them into one edit.");
            }
        }
    }

    private static String applyMatches(String content, List<Match> sortedMatches) {
        var builder = new StringBuilder(content.length());
        int cursor = 0;
        for (Match match : sortedMatches) {
            builder.append(content, cursor, match.start());
            builder.append(match.newText());
            cursor = match.end();
        }
        builder.append(content, cursor, content.length());
        return builder.toString();
    }

    private static String normalizeToLf(String text) {
        return text.replace("\r\n", "\n");
    }

    private static String label(int index, int total) {
        return total == 1 ? "oldText" : "edits[" + index + "]";
    }

    private record Edit(String oldText, String newText) {
    }

    private record Match(int editIndex, int start, int end, String newText) {
    }
}
