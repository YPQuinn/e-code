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

class WriteToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @TempDir
    Path tempDir;

    private final WriteTool tool = new WriteTool();

    @Test
    void exposesEditKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.EDIT);
        assertThat(tool.definition().name()).isEqualTo("write");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{\"path\":\"a.txt\",\"content\":\"hello\"}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"content\":\"\"}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"content\":\"hello\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"\",\"content\":\"hello\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\"}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitleWithoutTruncatingPathAndAlwaysNeedsPermission() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        String longPath = "dir/".repeat(40) + "file.java";
        var context = new ToolContext(cwd);

        assertThat(tool.title(args("{\"path\":\"" + longPath + "\",\"content\":\"\"}"))).isEqualTo("Write " + longPath);
        assertThat(tool.needsPermission(args("{\"path\":\"inside.txt\",\"content\":\"\"}"), context)).isTrue();
        assertThat(tool.needsPermission(args("{\"path\":\"" + outside + "\",\"content\":\"\"}"), context)).isTrue();
    }

    @Test
    void createsNewFileAndMissingParentDirectories() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        String content = "hello\n世界";

        String result = tool.execute(args("{\"path\":\"a/b/c.txt\",\"content\":\"hello\\n世界\"}"), new ToolContext(cwd));

        assertThat(result).isEqualTo("Successfully wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to a/b/c.txt");
        assertThat(Files.readString(cwd.resolve("a/b/c.txt"), StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void overwritesExistingFileAndSupportsEmptyContent() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path file = Files.writeString(cwd.resolve("note.txt"), "old content");
        var context = new ToolContext(cwd);

        String overwritten = tool.execute(args("{\"path\":\"note.txt\",\"content\":\"new\"}"), context);
        assertThat(overwritten).isEqualTo("Successfully wrote 3 bytes to note.txt");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("new");

        String emptied = tool.execute(args("{\"path\":\"note.txt\",\"content\":\"\"}"), context);
        assertThat(emptied).isEqualTo("Successfully wrote 0 bytes to note.txt");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void rejectsDirectoryPath() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(cwd.resolve("dir"));

        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"dir\",\"content\":\"x\"}"), new ToolContext(cwd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Path is a directory: dir");
    }

    private static JsonNode args(String json) {
        return JSON.readTree(json);
    }
}
