package io.agentic.pplee.ecode.core.tool.builtin;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrepToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Pattern TEMP_FILE_PATTERN = Pattern.compile("Full output saved to: ([^\\]]+)\\]");

    @TempDir
    Path tempDir;

    private final GrepTool tool = new GrepTool();

    @Test
    void exposesSearchKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.SEARCH);
        assertThat(tool.definition().name()).isEqualTo("grep");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{\"pattern\":\"foo\"}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate(
                "{\"pattern\":\"foo\",\"path\":\"src\",\"glob\":\"*.java\",\"ignoreCase\":true,\"literal\":true,\"context\":2,\"limit\":20}",
                InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"foo\",\"path\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"foo\",\"glob\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"foo\",\"context\":-1}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"foo\",\"limit\":0}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"foo\",\"ignoreCase\":\"yes\"}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitlesWithoutTruncatingPath() {
        String longPath = "dir/".repeat(40) + "src";

        assertThat(tool.title(args("{\"pattern\":\"TODO\"}"))).isEqualTo("Grep TODO");
        assertThat(tool.title(args("{\"pattern\":\"TODO\",\"path\":\"" + longPath + "\"}")))
                .isEqualTo("Grep TODO in " + longPath);
    }

    @Test
    void checksPermissionAgainstCwdAndSymlinks() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("a.txt"), "hello");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(cwd.resolve("escape.txt"), outside);
        var context = new ToolContext(cwd);

        assertThat(tool.needsPermission(args("{\"pattern\":\"hello\"}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"pattern\":\"hello\",\"path\":\"a.txt\"}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"pattern\":\"secret\",\"path\":\"" + outside + "\"}"), context)).isTrue();
        assertThat(tool.needsPermission(args("{\"pattern\":\"secret\",\"path\":\"escape.txt\"}"), context)).isTrue();
    }

    @Test
    void searchesWithJavaFallbackSupportingRegexLiteralCaseGlobContextAndSingleFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("a.java"), "one\nMatchOne\nthree\nMatchTwo\nfive\n");
        Files.writeString(cwd.resolve("b.txt"), "a.b[0] matchone\n");
        Files.write(cwd.resolve("bin.dat"), new byte[]{'M', 'a', 't', 'c', 'h', 0x00, 'X'});
        Files.createDirectories(cwd.resolve(".git"));
        Files.writeString(cwd.resolve(".git/config"), "MatchGit\n");

        GrepTool fallback = new GrepTool(100, 2000, 50 * 1024, 500, Optional::empty);
        var context = new ToolContext(cwd);

        assertThat(fallback.execute(args("{\"pattern\":\"Match(One|Two)\"}"), context))
                .isEqualTo("a.java:2: MatchOne\na.java:4: MatchTwo");
        assertThat(fallback.execute(args("{\"pattern\":\"a.b[0]\",\"literal\":true}"), context))
                .isEqualTo("b.txt:1: a.b[0] matchone");
        assertThat(fallback.execute(args("{\"pattern\":\"matchone\",\"ignoreCase\":true}"), context))
                .isEqualTo("a.java:2: MatchOne\nb.txt:1: a.b[0] matchone");
        assertThat(fallback.execute(args("{\"pattern\":\"matchone\",\"ignoreCase\":true,\"glob\":\"*.java\"}"), context))
                .isEqualTo("a.java:2: MatchOne");
        assertThat(fallback.execute(args("{\"pattern\":\"Match\",\"context\":1}"), context))
                .isEqualTo("a.java-1- one\na.java:2: MatchOne\na.java-3- three\na.java:4: MatchTwo\na.java-5- five");
        assertThat(fallback.execute(args("{\"pattern\":\"matchone\",\"path\":\"b.txt\"}"), context))
                .isEqualTo("b.txt:1: a.b[0] matchone");
        assertThat(fallback.execute(args("{\"pattern\":\"not-present\"}"), context))
                .isEqualTo("No matches found");

        assertThatThrownBy(() -> fallback.execute(args("{\"pattern\":\"[unclosed\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid regex pattern");
        assertThatThrownBy(() -> fallback.execute(args("{\"pattern\":\"x\",\"path\":\"missing\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Path not found: missing");
    }

    @Test
    void searchesWithRipgrepSupportingRegexLiteralContextGitignoreAndSingleFile() throws Exception {
        Assumptions.assumeTrue(Ripgrep.detect().isPresent());

        Path cwd = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(cwd.resolve(".git"));
        Files.writeString(cwd.resolve(".git/HEAD"), "needle\n");
        Files.writeString(cwd.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(cwd.resolve("ignored.txt"), "needle\n");
        Files.writeString(cwd.resolve("a.java"), "before\nneedle\nafter\n");
        Files.writeString(cwd.resolve("b.txt"), "NEEDLE a.b[0]\n");
        var context = new ToolContext(cwd);

        assertThat(tool.execute(args("{\"pattern\":\"needle\"}"), context))
                .isEqualTo("a.java:2: needle");
        assertThat(tool.execute(args("{\"pattern\":\"needle\",\"ignoreCase\":true}"), context))
                .isEqualTo("a.java:2: needle\nb.txt:1: NEEDLE a.b[0]");
        assertThat(tool.execute(args("{\"pattern\":\"a.b[0]\",\"literal\":true}"), context))
                .isEqualTo("b.txt:1: NEEDLE a.b[0]");
        assertThat(tool.execute(args("{\"pattern\":\"needle\",\"context\":1}"), context))
                .isEqualTo("a.java-1- before\na.java:2: needle\na.java-3- after");
        assertThat(tool.execute(args("{\"pattern\":\"needle\",\"path\":\"a.java\"}"), context))
                .isEqualTo("a.java:2: needle");
        assertThat(tool.execute(args("{\"pattern\":\"absent\"}"), context))
                .isEqualTo("No matches found");
    }

    @Test
    void truncatesByMatchLimitByteLimitAndLongLinesAndWritesTempFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("hits.txt"), "hit 1\nhit 2\nhit 3\nhit 4\n");
        var context = new ToolContext(cwd);

        GrepTool fallback = new GrepTool(100, 2000, 50 * 1024, 500, Optional::empty);
        String byLimit = fallback.execute(args("{\"pattern\":\"hit\",\"limit\":2}"), context);
        Path tempByLimit = extractTempFile(byLimit);
        try {
            assertThat(byLimit).startsWith("hits.txt:1: hit 1\nhits.txt:2: hit 2\n\n[Showing 2 of 4 matches. Full output saved to: ");
            assertThat(Files.readString(tempByLimit, StandardCharsets.UTF_8))
                    .isEqualTo("hits.txt:1: hit 1\nhits.txt:2: hit 2\nhits.txt:3: hit 3\nhits.txt:4: hit 4");
        } finally {
            Files.deleteIfExists(tempByLimit);
        }

        GrepTool byteCapped = new GrepTool(100, 2000, 36, 500, Optional::empty);
        String byBytes = byteCapped.execute(args("{\"pattern\":\"hit\"}"), context);
        Path tempByBytes = extractTempFile(byBytes);
        try {
            assertThat(byBytes).startsWith("hits.txt:1: hit 1\nhits.txt:2: hit 2\n\n[36B limit reached. Full output saved to: ");
            assertThat(Files.readString(tempByBytes, StandardCharsets.UTF_8))
                    .isEqualTo("hits.txt:1: hit 1\nhits.txt:2: hit 2\nhits.txt:3: hit 3\nhits.txt:4: hit 4");
        } finally {
            Files.deleteIfExists(tempByBytes);
        }

        Files.writeString(cwd.resolve("wide.txt"), "123456789\uD83D\uDE00tail\n");
        GrepTool lineCapped = new GrepTool(100, 2000, 50 * 1024, 10, Optional::empty);
        String longLine = lineCapped.execute(args("{\"pattern\":\"123\",\"path\":\"wide.txt\"}"), context);
        assertThat(longLine).isEqualTo(
                "wide.txt:1: 123456789... [truncated]\n\n[Some lines truncated to 10 chars]");
    }

    private static Path extractTempFile(String output) {
        Matcher matcher = TEMP_FILE_PATTERN.matcher(output);
        assertThat(matcher.find()).isTrue();
        return Path.of(matcher.group(1));
    }

    private static JsonNode args(String json) {
        return JSON.readTree(json);
    }
}
