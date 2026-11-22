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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @TempDir
    Path tempDir;

    private final EditTool tool = new EditTool();

    @Test
    void exposesEditKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.EDIT);
        assertThat(tool.definition().name()).isEqualTo("edit");
        assertThat(tool.definition().description()).contains("LINE_NUMBER: ");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{\"path\":\"a.txt\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"\"}]}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"edits\":[]}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"edits\":[{\"oldText\":\"\",\"newText\":\"b\"}]}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"edits\":[{\"oldText\":\"a\"}]}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitleWithoutTruncatingPathAndAlwaysNeedsPermission() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        String longPath = "dir/".repeat(40) + "file.java";
        var context = new ToolContext(cwd);

        assertThat(tool.title(args("{\"path\":\"" + longPath + "\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}")))
                .isEqualTo("Edit " + longPath);
        assertThat(tool.title(args("{\"path\":\"a.java\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"},{\"oldText\":\"c\",\"newText\":\"d\"}]}")))
                .isEqualTo("Edit a.java (2 edits)");
        assertThat(tool.needsPermission(args("{\"path\":\"a.java\",\"edits\":[{\"oldText\":\"a\",\"newText\":\"b\"}]}"), context))
                .isTrue();
    }

    @Test
    void replacesSingleMultipleOutOfOrderAndAdjacentBlocksAndDeletesText() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path file = Files.writeString(cwd.resolve("code.txt"), "alpha\nbeta\ngamma\ndelta\n");
        var context = new ToolContext(cwd);

        String multi = tool.execute(args("""
                {
                  "path": "code.txt",
                  "edits": [
                    { "oldText": "delta", "newText": "DELTA" },
                    { "oldText": "alpha", "newText": "ALPHA" }
                  ]
                }
                """), context);

        assertThat(multi).isEqualTo("Successfully replaced 2 block(s) in code.txt");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("ALPHA\nbeta\ngamma\nDELTA\n");

        String deleted = tool.execute(args("""
                {
                  "path": "code.txt",
                  "edits": [
                    { "oldText": "beta\\n", "newText": "" },
                    { "oldText": "gamma\\n", "newText": "GAMMA\\n" }
                  ]
                }
                """), context);

        assertThat(deleted).isEqualTo("Successfully replaced 2 block(s) in code.txt");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("ALPHA\nGAMMA\nDELTA\n");
    }

    @Test
    void rejectsNotFoundDuplicateOverlapAndNoOpEditsWithoutModifyingFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        String original = "aaa\nfoo\nfoo\nabcdef\n";
        Path file = Files.writeString(cwd.resolve("sample.txt"), original);
        var context = new ToolContext(cwd);

        assertThatThrownBy(() -> tool.execute(args("""
                { "path": "sample.txt", "edits": [{ "oldText": "missing", "newText": "x" }] }
                """), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not find oldText");

        assertThatThrownBy(() -> tool.execute(args("""
                { "path": "sample.txt", "edits": [{ "oldText": "foo", "newText": "bar" }] }
                """), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Found 2 occurrences");

        assertThatThrownBy(() -> tool.execute(args("""
                { "path": "sample.txt", "edits": [{ "oldText": "aa", "newText": "bb" }] }
                """), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Found 2 occurrences");

        assertThatThrownBy(() -> tool.execute(args("""
                {
                  "path": "sample.txt",
                  "edits": [
                    { "oldText": "abcd", "newText": "X" },
                    { "oldText": "cdef", "newText": "Y" }
                  ]
                }
                """), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");

        assertThatThrownBy(() -> tool.execute(args("""
                { "path": "sample.txt", "edits": [{ "oldText": "abcdef", "newText": "abcdef" }] }
                """), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No changes made");

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void rejectsMissingFileAndDirectory() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(cwd.resolve("dir"));
        var context = new ToolContext(cwd);

        assertThatThrownBy(() -> tool.execute(args("""
                { "path": "missing.txt", "edits": [{ "oldText": "a", "newText": "b" }] }
                """), context))
                .hasMessage("File not found: missing.txt");

        assertThatThrownBy(() -> tool.execute(args("""
                { "path": "dir", "edits": [{ "oldText": "a", "newText": "b" }] }
                """), context))
                .hasMessage("Path is a directory: dir");
    }

    @Test
    void preservesUtf8BomAndCrlfLineEndings() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path file = Files.writeString(cwd.resolve("win.txt"), "\uFEFFfirst\r\nsecond\r\nthird\r\n", StandardCharsets.UTF_8);

        String result = tool.execute(args("""
                {
                  "path": "win.txt",
                  "edits": [
                    { "oldText": "first\\nsecond", "newText": "FIRST\\nSECOND" }
                  ]
                }
                """), new ToolContext(cwd));

        assertThat(result).isEqualTo("Successfully replaced 1 block(s) in win.txt");
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .isEqualTo("\uFEFFFIRST\r\nSECOND\r\nthird\r\n");
    }

    private static JsonNode args(String json) {
        return JSON.readTree(json);
    }
}
