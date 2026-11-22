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
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GrepTool implements Tool {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LINE_LENGTH = 500;

    private static final int BINARY_PROBE_BYTES = 8 * 1024;
    private static final Set<String> FALLBACK_SKIPPED_DIRS = Set.of(".git", "node_modules", "target", "build");
    private static final Comparator<String> PATH_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "grep",
            "Search file contents for a pattern. Returns matching lines with file paths and line numbers. Respects .gitignore when ripgrep is available. Output is truncated to 100 matches, 2000 lines, or 50KB (whichever is hit first). Long lines are truncated to 500 chars.",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "pattern": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Search pattern (regex or literal string)"
                        },
                        "path": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Directory or file to search (default: current directory)"
                        },
                        "glob": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Filter files by glob pattern, e.g. '*.java' or 'src/**/*.java'"
                        },
                        "ignoreCase": {
                          "type": "boolean",
                          "description": "Case-insensitive search (default: false)"
                        },
                        "literal": {
                          "type": "boolean",
                          "description": "Treat pattern as literal string instead of regex (default: false)"
                        },
                        "context": {
                          "type": "integer",
                          "minimum": 0,
                          "description": "Number of lines to show before and after each match (default: 0)"
                        },
                        "limit": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "Maximum number of matches to return (default: 100)"
                        }
                      },
                      "required": ["pattern"]
                    }
                    """));

    private final int defaultLimit;
    private final int maxLines;
    private final int maxBytes;
    private final int maxLineLength;
    private final Supplier<Optional<Ripgrep>> ripgrepSupplier;

    public GrepTool() {
        this(
                DEFAULT_LIMIT,
                OutputTruncation.DEFAULT_MAX_LINES,
                OutputTruncation.DEFAULT_MAX_BYTES,
                MAX_LINE_LENGTH,
                Ripgrep::detect);
    }

    GrepTool(
            int defaultLimit,
            int maxLines,
            int maxBytes,
            int maxLineLength,
            Supplier<Optional<Ripgrep>> ripgrepSupplier) {
        this.defaultLimit = defaultLimit;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
        this.maxLineLength = maxLineLength;
        this.ripgrepSupplier = ripgrepSupplier;
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
        String pattern = arguments.path("pattern").asString("");
        JsonNode pathNode = arguments.path("path");
        if (pathNode.isMissingNode() || pathNode.isNull()) {
            return "Grep " + pattern;
        }
        return "Grep " + pattern + " in " + pathNode.asString(".");
    }

    @Override
    public boolean needsPermission(JsonNode arguments, ToolContext context) {
        Path target = ToolPaths.resolve(rawPathOrDot(arguments), context.cwd());
        return !ToolPaths.isWithinCwd(target, context.cwd());
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        String pattern = arguments.path("pattern").asString("");
        String rawPath = rawPathOrDot(arguments);
        String glob = optionalString(arguments, "glob");
        boolean ignoreCase = optionalBoolean(arguments, "ignoreCase");
        boolean literal = optionalBoolean(arguments, "literal");
        int contextLines = optionalInt(arguments, "context", 0);
        int matchLimit = optionalInt(arguments, "limit", defaultLimit);

        Path target = ToolPaths.resolve(rawPath, context.cwd());
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Path not found: " + rawPath);
        }

        SearchRequest request = new SearchRequest(pattern, glob, ignoreCase, literal, contextLines);
        Optional<Ripgrep> ripgrep = ripgrepSupplier.get();
        SearchResult result = ripgrep.isPresent()
                ? searchWithRipgrep(ripgrep.get(), target, request)
                : searchWithJava(target, request);

        if (result.totalMatches() == 0) {
            return "No matches found";
        }
        return formatResult(result, matchLimit, contextLines);
    }

    private SearchResult searchWithRipgrep(Ripgrep ripgrep, Path target, SearchRequest request)
            throws IOException, InterruptedException {
        boolean isDirectory = Files.isDirectory(target);
        Path workDir = isDirectory ? target : target.getParent();
        List<String> args = new ArrayList<>();
        args.add("--no-config");
        args.add("--json");
        args.add("--line-number");
        args.add("--color=never");
        args.add("--hidden");
        if (!Ripgrep.isInsideGitRepo(workDir)) {
            args.add("--no-require-git");
        }
        if (request.ignoreCase()) {
            args.add("--ignore-case");
        }
        if (request.literal()) {
            args.add("--fixed-strings");
        }
        if (request.contextLines() > 0) {
            args.add("--context");
            args.add(Integer.toString(request.contextLines()));
        }
        if (request.glob() != null && isDirectory) {
            args.add("--glob");
            args.add(normalizeGlob(request.glob()));
        }
        if (isDirectory) {
            args.add("--glob");
            args.add("!.git");
            args.add("--glob");
            args.add("!.git/**");
        }
        args.add("--");
        args.add(request.pattern());
        args.add(isDirectory ? "." : target.getFileName().toString());

        Ripgrep.Execution execution = ripgrep.run(workDir, args);
        if (execution.exitCode() == 1) {
            return new SearchResult(List.of(), 0, false);
        }
        if (execution.exitCode() != 0) {
            String message = execution.stderr().isEmpty()
                    ? "ripgrep exited with code " + execution.exitCode()
                    : execution.stderr();
            throw new IllegalArgumentException("grep failed: " + message);
        }

        Map<String, List<OutputLine>> byFile = new LinkedHashMap<>();
        int totalMatches = 0;
        boolean anyLineTruncated = false;

        for (String rawJson : execution.lines()) {
            if (rawJson.isBlank()) {
                continue;
            }
            JsonNode event = JSON.readTree(rawJson);
            String type = event.path("type").asString("");
            boolean isMatch = type.equals("match");
            if (!isMatch && !type.equals("context")) {
                continue;
            }
            JsonNode data = event.path("data");
            String filePath = formatRgPath(decodeRgText(data.path("path")), target, isDirectory);
            int lineNumber = data.path("line_number").intValue();
            String lineText = stripLineEnding(decodeRgText(data.path("lines")));
            TruncatedLine truncated = truncateLine(lineText);
            if (truncated.wasTruncated()) {
                anyLineTruncated = true;
            }
            String formatted = formatLine(filePath, lineNumber, truncated.text(), isMatch);
            byFile.computeIfAbsent(filePath, ignored -> new ArrayList<>())
                    .add(new OutputLine(filePath, lineNumber, formatted, isMatch));
            if (isMatch) {
                totalMatches++;
            }
        }

        List<String> sortedFiles = new ArrayList<>(byFile.keySet());
        sortedFiles.sort(PATH_ORDER);
        List<OutputLine> ordered = new ArrayList<>();
        for (String file : sortedFiles) {
            ordered.addAll(byFile.get(file));
        }
        return new SearchResult(ordered, totalMatches, anyLineTruncated);
    }

    private SearchResult searchWithJava(Path target, SearchRequest request) throws IOException, InterruptedException {
        Pattern compiled = compilePattern(request);
        List<Path> files = collectFiles(target, request.glob());
        List<OutputLine> output = new ArrayList<>();
        int totalMatches = 0;
        boolean anyLineTruncated = false;
        boolean isDirectory = Files.isDirectory(target);

        for (Path file : files) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (isBinary(file)) {
                continue;
            }
            List<String> lines = readAllLinesLossy(file);
            boolean[] matched = new boolean[lines.size()];
            int fileMatches = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (compiled.matcher(lines.get(i)).find()) {
                    matched[i] = true;
                    fileMatches++;
                }
            }
            if (fileMatches == 0) {
                continue;
            }

            boolean[] included = new boolean[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                if (!matched[i]) {
                    continue;
                }
                int from = Math.max(0, i - request.contextLines());
                int to = Math.min(lines.size() - 1, i + request.contextLines());
                for (int j = from; j <= to; j++) {
                    included[j] = true;
                }
            }

            String displayPath = isDirectory
                    ? target.relativize(file).toString().replace('\\', '/')
                    : file.getFileName().toString();
            for (int i = 0; i < lines.size(); i++) {
                if (!included[i]) {
                    continue;
                }
                TruncatedLine truncated = truncateLine(lines.get(i));
                if (truncated.wasTruncated()) {
                    anyLineTruncated = true;
                }
                int lineNumber = i + 1;
                String formatted = formatLine(displayPath, lineNumber, truncated.text(), matched[i]);
                output.add(new OutputLine(displayPath, lineNumber, formatted, matched[i]));
            }
            totalMatches += fileMatches;
        }

        return new SearchResult(output, totalMatches, anyLineTruncated);
    }

    private String formatResult(SearchResult result, int matchLimit, int contextLines) throws IOException {
        List<String> fullLines = result.lines().stream().map(OutputLine::formatted).toList();
        String fullOutput = String.join("\n", fullLines);

        List<String> limitedLines = new ArrayList<>();
        int shownMatches = 0;
        boolean matchLimitReached = false;
        String limitFile = null;
        int trailingContextEnd = 0;

        for (OutputLine line : result.lines()) {
            if (shownMatches < matchLimit) {
                limitedLines.add(line.formatted());
                if (line.isMatch()) {
                    shownMatches++;
                    if (shownMatches == matchLimit) {
                        limitFile = line.filePath();
                        trailingContextEnd = line.lineNumber() + contextLines;
                    }
                }
                continue;
            }
            boolean inWindow = line.filePath().equals(limitFile) && line.lineNumber() <= trailingContextEnd;
            if (inWindow && !line.isMatch()) {
                limitedLines.add(line.formatted());
            }
            if (line.isMatch()) {
                matchLimitReached = true;
            }
        }

        String candidate = String.join("\n", limitedLines);
        OutputTruncation.Result truncation = OutputTruncation.truncateHead(candidate, maxLines, maxBytes);
        boolean truncatedByAny = matchLimitReached || truncation.truncated();

        List<String> notices = new ArrayList<>();
        if (matchLimitReached) {
            notices.add("Showing " + matchLimit + " of " + result.totalMatches() + " matches");
        }
        if (truncation.truncated()) {
            if (truncation.reason() == OutputTruncation.Reason.BYTES) {
                notices.add(OutputTruncation.formatSize(maxBytes) + " limit reached");
            } else {
                notices.add(maxLines + " lines limit reached");
            }
        }
        if (result.anyLineTruncated()) {
            notices.add("Some lines truncated to " + maxLineLength + " chars");
        }
        if (truncatedByAny) {
            Path tempFile = OutputTruncation.writeTempFile("grep", fullOutput);
            notices.add("Full output saved to: " + tempFile);
        }

        if (notices.isEmpty()) {
            return truncation.content();
        }
        if (truncation.content().isEmpty()) {
            return "[" + String.join(". ", notices) + "]";
        }
        return truncation.content() + "\n\n[" + String.join(". ", notices) + "]";
    }

    private List<Path> collectFiles(Path target, String glob) throws IOException, InterruptedException {
        if (!Files.isDirectory(target)) {
            return List.of(target);
        }
        String normalizedGlob = glob == null ? null : normalizeGlob(glob);
        boolean matchByName = normalizedGlob != null && !normalizedGlob.contains("/");
        List<PathMatcher> matchers = normalizedGlob == null
                ? List.of()
                : buildMatchers(target.getFileSystem(), normalizedGlob);

        List<Path> files = new ArrayList<>();
        InterruptedException[] interrupted = new InterruptedException[1];
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted[0] = new InterruptedException();
                    return FileVisitResult.TERMINATE;
                }
                if (!current.equals(target) && FALLBACK_SKIPPED_DIRS.contains(current.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted[0] = new InterruptedException();
                    return FileVisitResult.TERMINATE;
                }
                if (matchers.isEmpty()) {
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
                Path relative = target.relativize(file);
                Path candidate = matchByName ? relative.getFileName() : relative;
                if (matchesAny(matchers, candidate)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (interrupted[0] != null) {
            throw interrupted[0];
        }
        files.sort(Comparator.comparing(p -> target.relativize(p).toString().replace('\\', '/'), PATH_ORDER));
        return files;
    }

    private TruncatedLine truncateLine(String line) {
        if (line.length() <= maxLineLength) {
            return new TruncatedLine(line, false);
        }
        int cut = maxLineLength;
        if (cut > 0
                && Character.isHighSurrogate(line.charAt(cut - 1))
                && Character.isLowSurrogate(line.charAt(cut))) {
            cut--;
        }
        return new TruncatedLine(line.substring(0, cut) + "... [truncated]", true);
    }

    private static Pattern compilePattern(SearchRequest request) {
        String source = request.literal() ? Pattern.quote(request.pattern()) : request.pattern();
        int flags = request.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        try {
            return Pattern.compile(source, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + e.getDescription());
        }
    }

    private static List<String> readAllLinesLossy(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
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

    private static List<PathMatcher> buildMatchers(FileSystem fileSystem, String pattern) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(pattern);
        if (pattern.startsWith("**/")) {
            variants.add(pattern.substring(3));
        }
        for (String candidate : List.copyOf(variants)) {
            if (candidate.contains("/**/")) {
                variants.add(candidate.replace("/**/", "/"));
            }
        }
        List<PathMatcher> matchers = new ArrayList<>(variants.size());
        for (String variant : variants) {
            matchers.add(fileSystem.getPathMatcher("glob:" + variant));
        }
        return matchers;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, Path candidate) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String decodeRgText(JsonNode node) {
        JsonNode text = node.path("text");
        if (!text.isMissingNode() && !text.isNull()) {
            return text.asString("");
        }
        JsonNode bytes = node.path("bytes");
        if (!bytes.isMissingNode() && !bytes.isNull()) {
            return new String(Base64.getDecoder().decode(bytes.asString("")), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String formatRgPath(String rawPath, Path target, boolean isDirectory) {
        if (!isDirectory) {
            return target.getFileName().toString();
        }
        String normalized = rawPath.replace('\\', '/');
        return normalized.startsWith("./") ? normalized.substring(2) : normalized;
    }

    private static String formatLine(String filePath, int lineNumber, String text, boolean isMatch) {
        return isMatch
                ? filePath + ":" + lineNumber + ": " + text
                : filePath + "-" + lineNumber + "- " + text;
    }

    private static String stripLineEnding(String line) {
        if (line.endsWith("\r\n")) {
            return line.substring(0, line.length() - 2);
        }
        if (line.endsWith("\n") || line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static String normalizeGlob(String glob) {
        String normalized = glob.replace('\\', '/');
        if (normalized.startsWith("!./")) {
            return "!" + normalized.substring(3);
        }
        return normalized.startsWith("./") ? normalized.substring(2) : normalized;
    }

    private static String rawPathOrDot(JsonNode arguments) {
        String value = optionalString(arguments, "path");
        return value == null ? "." : value;
    }

    private static String optionalString(JsonNode arguments, String field) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asString("");
    }

    private static boolean optionalBoolean(JsonNode arguments, String field) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        return node.asBoolean(false);
    }

    private static int optionalInt(JsonNode arguments, String field, int fallback) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.intValue();
    }

    private record SearchRequest(String pattern, String glob, boolean ignoreCase, boolean literal, int contextLines) {
    }

    private record OutputLine(String filePath, int lineNumber, String formatted, boolean isMatch) {
    }

    private record SearchResult(List<OutputLine> lines, int totalMatches, boolean anyLineTruncated) {
    }

    private record TruncatedLine(String text, boolean wasTruncated) {
    }
}
