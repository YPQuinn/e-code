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

class FindToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Pattern TEMP_FILE_PATTERN = Pattern.compile("Full output saved to: ([^\\]]+)\\]");

    @TempDir
    Path tempDir;

    private final FindTool tool = new FindTool();

    @Test
    void exposesSearchKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.SEARCH);
        assertThat(tool.definition().name()).isEqualTo("find");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{\"pattern\":\"*.java\"}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"pattern\":\"src/**/*.java\",\"path\":\"src\",\"limit\":50}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"*.java\",\"path\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"*.java\",\"limit\":0}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"pattern\":\"*.java\",\"limit\":1.5}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitlesWithoutTruncatingPath() {
        String longPath = "dir/".repeat(40) + "src";

        assertThat(tool.title(args("{\"pattern\":\"*.java\"}"))).isEqualTo("Find *.java");
        assertThat(tool.title(args("{\"pattern\":\"*.java\",\"path\":\"" + longPath + "\"}")))
                .isEqualTo("Find *.java in " + longPath);
    }

    @Test
    void checksPermissionAgainstCwdAndSymlinks() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(cwd.resolve("src"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(cwd.resolve("escape"), outside);
        var context = new ToolContext(cwd);

        assertThat(tool.needsPermission(args("{\"pattern\":\"*.java\"}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"pattern\":\"*.java\",\"path\":\"src\"}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"pattern\":\"*.java\",\"path\":\"" + outside + "\"}"), context)).isTrue();
        assertThat(tool.needsPermission(args("{\"pattern\":\"*.java\",\"path\":\"escape\"}"), context)).isTrue();
    }

    @Test
    void findsFilesWithJavaFallbackAcrossGlobFormsAndSkipsDefaultDirs() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("Root.java"), "");
        Files.createDirectories(cwd.resolve("src/sub"));
        Files.writeString(cwd.resolve("src/Main.java"), "");
        Files.writeString(cwd.resolve("src/sub/Helper.java"), "");
        Files.createDirectories(cwd.resolve(".git"));
        Files.writeString(cwd.resolve(".git/Inside.java"), "");
        Files.createDirectories(cwd.resolve("target"));
        Files.writeString(cwd.resolve("target/Gen.java"), "");
        Files.createDirectories(cwd.resolve("node_modules"));
        Files.writeString(cwd.resolve("node_modules/Dep.java"), "");

        FindTool fallback = new FindTool(1000, 2000, 50 * 1024, Optional::empty);
        var context = new ToolContext(cwd);

        assertThat(fallback.execute(args("{\"pattern\":\"*.java\"}"), context))
                .isEqualTo("Root.java\nsrc/Main.java\nsrc/sub/Helper.java");
        assertThat(fallback.execute(args("{\"pattern\":\"**/*.java\"}"), context))
                .isEqualTo("Root.java\nsrc/Main.java\nsrc/sub/Helper.java");
        assertThat(fallback.execute(args("{\"pattern\":\"src/**/*.java\"}"), context))
                .isEqualTo("src/Main.java\nsrc/sub/Helper.java");
        assertThat(fallback.execute(args("{\"pattern\":\"*.rs\"}"), context))
                .isEqualTo("No files found matching pattern");
    }

    @Test
    void findsFilesWithRipgrepAndRespectsGitignore() throws Exception {
        Assumptions.assumeTrue(Ripgrep.detect().isPresent());

        Path cwd = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(cwd.resolve(".git"));
        Files.writeString(cwd.resolve(".git/Internal.java"), "");
        Files.writeString(cwd.resolve(".gitignore"), "ignored.java\n");
        Files.writeString(cwd.resolve("ignored.java"), "");
        Files.writeString(cwd.resolve(".hidden.java"), "");
        Files.writeString(cwd.resolve("Root.java"), "");
        Files.createDirectories(cwd.resolve("src/sub"));
        Files.writeString(cwd.resolve("src/sub/App.java"), "");
        var context = new ToolContext(cwd);

        assertThat(tool.execute(args("{\"pattern\":\"*.java\"}"), context))
                .isEqualTo(".hidden.java\nRoot.java\nsrc/sub/App.java");
        assertThat(tool.execute(args("{\"pattern\":\"src/**/*.java\"}"), context))
                .isEqualTo("src/sub/App.java");
        assertThat(tool.execute(args("{\"pattern\":\"*.rs\"}"), context))
                .isEqualTo("No files found matching pattern");
    }

    @Test
    void rejectsMissingPathAndFilePath() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("file.txt"), "x");
        var context = new ToolContext(cwd);

        assertThatThrownBy(() -> tool.execute(args("{\"pattern\":\"*.txt\",\"path\":\"missing\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Path not found: missing");
        assertThatThrownBy(() -> tool.execute(args("{\"pattern\":\"*.txt\",\"path\":\"file.txt\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a directory: file.txt");
    }

    @Test
    void truncatesByLimitAndBytesAndWritesFullOutputToTempFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("a.java"), "");
        Files.writeString(cwd.resolve("b.java"), "");
        Files.writeString(cwd.resolve("c.java"), "");
        Files.writeString(cwd.resolve("d.java"), "");
        var context = new ToolContext(cwd);

        FindTool fallback = new FindTool(1000, 2000, 50 * 1024, Optional::empty);
        String byLimit = fallback.execute(args("{\"pattern\":\"*.java\",\"limit\":2}"), context);
        Path tempByLimit = extractTempFile(byLimit);
        try {
            assertThat(byLimit).startsWith("a.java\nb.java\n\n[Showing 2 of 4 results (limit 2). Full output saved to: ");
            assertThat(Files.readString(tempByLimit, StandardCharsets.UTF_8)).isEqualTo("a.java\nb.java\nc.java\nd.java");
        } finally {
            Files.deleteIfExists(tempByLimit);
        }

        FindTool byteCapped = new FindTool(1000, 2000, 14, Optional::empty);
        String byBytes = byteCapped.execute(args("{\"pattern\":\"*.java\"}"), context);
        Path tempByBytes = extractTempFile(byBytes);
        try {
            assertThat(byBytes).startsWith("a.java\nb.java\n\n[Showing 2 of 4 results (14B limit). Full output saved to: ");
            assertThat(Files.readString(tempByBytes, StandardCharsets.UTF_8)).isEqualTo("a.java\nb.java\nc.java\nd.java");
        } finally {
            Files.deleteIfExists(tempByBytes);
        }
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
