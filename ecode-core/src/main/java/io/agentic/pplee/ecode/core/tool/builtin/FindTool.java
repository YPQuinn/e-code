package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class FindTool implements Tool {

    static final int DEFAULT_LIMIT = 1000;

    private static final Set<String> FALLBACK_SKIPPED_DIRS = Set.of(".git", "node_modules", "target", "build");
    private static final Comparator<String> PATH_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "find",
            "Search for files by glob pattern. Returns matching file paths relative to the search directory. Respects .gitignore when ripgrep is available. Output is truncated to 1000 results, 2000 lines, or 50KB (whichever is hit first).",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "pattern": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Glob pattern to match files, e.g. '*.java', '**/*.json', or 'src/**/*.java'"
                        },
                        "path": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Directory to search in (default: current directory)"
                        },
                        "limit": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "Maximum number of results (default: 1000)"
                        }
                      },
                      "required": ["pattern"]
                    }
                    """));

    private final int defaultLimit;
    private final int maxLines;
    private final int maxBytes;
    private final Supplier<Optional<Ripgrep>> ripgrepSupplier;

    public FindTool() {
        this(DEFAULT_LIMIT, OutputTruncation.DEFAULT_MAX_LINES, OutputTruncation.DEFAULT_MAX_BYTES, Ripgrep::detect);
    }

    FindTool(int defaultLimit, int maxLines, int maxBytes, Supplier<Optional<Ripgrep>> ripgrepSupplier) {
        this.defaultLimit = defaultLimit;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
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
            return "Find " + pattern;
        }
        return "Find " + pattern + " in " + pathNode.asString(".");
    }

    @Override
    public boolean needsPermission(JsonNode arguments, ToolContext context) {
        Path target = ToolPaths.resolve(rawPathOrDot(arguments), context.cwd());
        return !ToolPaths.isWithinCwd(target, context.cwd());
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        String rawPattern = arguments.path("pattern").asString("");
        String rawPath = rawPathOrDot(arguments);
        int resultLimit = optionalInt(arguments, "limit", defaultLimit);
        Path dir = ToolPaths.resolve(rawPath, context.cwd());
        validateDirectory(dir, rawPath);

        String pattern = normalizePattern(rawPattern);
        Optional<Ripgrep> ripgrep = ripgrepSupplier.get();
        List<String> matches = ripgrep.isPresent()
                ? findWithRipgrep(ripgrep.get(), dir, pattern)
                : findWithJava(dir, pattern);

        if (matches.isEmpty()) {
            return "No files found matching pattern";
        }

        matches.sort(PATH_ORDER);
        String fullOutput = String.join("\n", matches);
        int effectiveMaxLines = Math.min(resultLimit, maxLines);
        OutputTruncation.Result truncation = OutputTruncation.truncateHead(fullOutput, effectiveMaxLines, maxBytes);
        if (!truncation.truncated()) {
            return truncation.content();
        }

        Path tempFile = OutputTruncation.writeTempFile("find", fullOutput);
        return formatTruncated(truncation, effectiveMaxLines, tempFile);
    }

    private List<String> findWithRipgrep(Ripgrep ripgrep, Path dir, String pattern)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add("--files");
        args.add("--hidden");
        args.add("--color=never");
        if (!Ripgrep.isInsideGitRepo(dir)) {
            args.add("--no-require-git");
        }
        args.add("--glob");
        args.add("!.git");
        args.add("--glob");
        args.add("!.git/**");

        Ripgrep.Execution execution = ripgrep.run(dir, args);
        if (execution.exitCode() == 1) {
            return new ArrayList<>();
        }
        if (execution.exitCode() != 0) {
            String message = execution.stderr().isEmpty()
                    ? "ripgrep exited with code " + execution.exitCode()
                    : execution.stderr();
            throw new IllegalArgumentException("find failed: " + message);
        }

        boolean matchByName = !pattern.contains("/");
        FileSystem fileSystem = dir.getFileSystem();
        List<PathMatcher> matchers = buildMatchers(fileSystem, pattern);
        List<String> results = new ArrayList<>();
        for (String line : execution.lines()) {
            if (line.isEmpty()) {
                continue;
            }
            String normalized = stripLeadingDotSlash(line.replace('\\', '/'));
            Path relative = fileSystem.getPath(normalized);
            Path candidate = matchByName ? relative.getFileName() : relative;
            if (matchesAny(matchers, candidate)) {
                results.add(normalized);
            }
        }
        return results;
    }

    private List<String> findWithJava(Path dir, String pattern) throws IOException, InterruptedException {
        boolean matchByName = !pattern.contains("/");
        List<PathMatcher> matchers = buildMatchers(dir.getFileSystem(), pattern);
        List<String> results = new ArrayList<>();
        InterruptedException[] interrupted = new InterruptedException[1];

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted[0] = new InterruptedException();
                    return FileVisitResult.TERMINATE;
                }
                if (!current.equals(dir) && FALLBACK_SKIPPED_DIRS.contains(current.getFileName().toString())) {
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
                Path relative = dir.relativize(file);
                Path candidate = matchByName ? relative.getFileName() : relative;
                if (matchesAny(matchers, candidate)) {
                    results.add(relative.toString().replace('\\', '/'));
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
        return results;
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

    private void validateDirectory(Path dir, String rawPath) {
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("Path not found: " + rawPath);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + rawPath);
        }
    }

    private String formatTruncated(OutputTruncation.Result truncation, int effectiveMaxLines, Path tempFile) {
        if (truncation.firstLineExceedsLimit()) {
            return "[Entry exceeds " + OutputTruncation.formatSize(maxBytes)
                    + " limit. Full output saved to: " + tempFile + "]";
        }
        if (truncation.reason() == OutputTruncation.Reason.BYTES) {
            return truncation.content() + "\n\n[Showing " + truncation.outputLines() + " of "
                    + truncation.totalLines() + " results (" + OutputTruncation.formatSize(maxBytes)
                    + " limit). Full output saved to: " + tempFile + "]";
        }
        return truncation.content() + "\n\n[Showing " + truncation.outputLines() + " of "
                + truncation.totalLines() + " results (limit " + effectiveMaxLines
                + "). Full output saved to: " + tempFile + "]";
    }

    private static String normalizePattern(String rawPattern) {
        return stripLeadingDotSlash(rawPattern.replace('\\', '/'));
    }

    private static String stripLeadingDotSlash(String value) {
        return value.startsWith("./") ? value.substring(2) : value;
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
