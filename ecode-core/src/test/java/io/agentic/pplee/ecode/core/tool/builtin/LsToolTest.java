package io.agentic.pplee.ecode.core.tool.builtin;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LsToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Pattern TEMP_FILE_PATTERN = Pattern.compile("Full output saved to: ([^\\]]+)\\]");

    @TempDir
    Path tempDir;

    private final LsTool tool = new LsTool();

    @Test
    void exposesSearchKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.SEARCH);
        assertThat(tool.definition().name()).isEqualTo("ls");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"path\":\"src\",\"limit\":10}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"path\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"limit\":0}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"limit\":1.5}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitlesWithoutTruncatingPath() {
        String longPath = "dir/".repeat(40) + "leaf";

        assertThat(tool.title(args("{}"))).isEqualTo("List .");
        assertThat(tool.title(args("{\"path\":\"" + longPath + "\"}"))).isEqualTo("List " + longPath);
    }

    @Test
    void checksPermissionAgainstCwdAndSymlinks() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(cwd.resolve("sub"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(cwd.resolve("escape"), outside);
        var context = new ToolContext(cwd);

        assertThat(tool.needsPermission(args("{}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"path\":\"sub\"}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"path\":\"" + outside + "\"}"), context)).isTrue();
        assertThat(tool.needsPermission(args("{\"path\":\"escape\"}"), context)).isTrue();
    }

    @Test
    void listsEntriesSortedCaseInsensitivelyWithDirectorySuffixAndDotfiles() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve(".env"), "k=v");
        Files.writeString(cwd.resolve("b.txt"), "b");
        Files.writeString(cwd.resolve("a.txt"), "a");
        Files.createDirectories(cwd.resolve("Alpha"));
        Path targetDir = Files.createDirectories(cwd.resolve("dir-target"));
        Files.createSymbolicLink(cwd.resolve("link-dir"), targetDir);

        String output = tool.execute(args("{}"), new ToolContext(cwd));

        assertThat(output).isEqualTo(".env\na.txt\nAlpha/\nb.txt\ndir-target/\nlink-dir/");
    }

    @Test
    void returnsEmptyDirectoryMarker() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("empty"));

        String output = tool.execute(args("{}"), new ToolContext(cwd));

        assertThat(output).isEqualTo("(empty directory)");
    }

    @Test
    void rejectsMissingPathAndFilePath() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("file.txt"), "data");
        var context = new ToolContext(cwd);

        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"missing\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Path not found: missing");
        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"file.txt\"}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a directory: file.txt");
    }

    @Test
    void truncatesByEntryLimitAndWritesFullOutputToTempFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("a.txt"), "");
        Files.writeString(cwd.resolve("b.txt"), "");
        Files.writeString(cwd.resolve("c.txt"), "");
        Files.writeString(cwd.resolve("d.txt"), "");

        String output = tool.execute(args("{\"limit\":2}"), new ToolContext(cwd));
        Path tempFile = extractTempFile(output);
        try {
            assertThat(output).startsWith("a.txt\nb.txt\n\n[Showing 2 of 4 entries (limit 2). Full output saved to: ");
            assertThat(Files.readString(tempFile, StandardCharsets.UTF_8)).isEqualTo("a.txt\nb.txt\nc.txt\nd.txt");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void truncatesByByteLimitAndWritesFullOutputToTempFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("alpha.txt"), "");
        Files.writeString(cwd.resolve("bravo.txt"), "");
        Files.writeString(cwd.resolve("charlie.txt"), "");

        LsTool byteCapped = new LsTool(500, 2000, 20);
        String output = byteCapped.execute(args("{}"), new ToolContext(cwd));
        Path tempFile = extractTempFile(output);
        try {
            assertThat(output).startsWith("alpha.txt\nbravo.txt\n\n[Showing 2 of 3 entries (20B limit). Full output saved to: ");
            assertThat(Files.readString(tempFile, StandardCharsets.UTF_8))
                    .isEqualTo("alpha.txt\nbravo.txt\ncharlie.txt");
        } finally {
            Files.deleteIfExists(tempFile);
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
