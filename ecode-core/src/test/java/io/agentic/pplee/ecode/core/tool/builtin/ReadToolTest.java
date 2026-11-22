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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @TempDir
    Path tempDir;

    private final ReadTool tool = new ReadTool();

    @Test
    void exposesReadKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.READ);
        assertThat(tool.definition().name()).isEqualTo("read");
        assertThat(tool.definition().description()).contains("LINE_NUMBER: CONTENT");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{\"path\":\"a.txt\"}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"offset\":1,\"limit\":10}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"offset\":0}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"limit\":-1}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"path\":\"a.txt\",\"offset\":1.5}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitlesWithoutTruncatingPath() {
        String longPath = "a/".repeat(60) + "file.java";

        assertThat(tool.title(args("{\"path\":\"" + longPath + "\"}"))).isEqualTo("Read " + longPath);
        assertThat(tool.title(args("{\"path\":\"a.java\",\"offset\":10}")))
                .isEqualTo("Read a.java (from line 10)");
        assertThat(tool.title(args("{\"path\":\"a.java\",\"limit\":25}")))
                .isEqualTo("Read a.java (first 25 lines)");
        assertThat(tool.title(args("{\"path\":\"a.java\",\"offset\":10,\"limit\":25}")))
                .isEqualTo("Read a.java (lines 10-34)");
    }

    @Test
    void checksPermissionAgainstCwdAndSymlinks() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("inside.txt"), "ok");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(cwd.resolve("escape.txt"), outside);
        var context = new ToolContext(cwd);

        assertThat(tool.needsPermission(args("{\"path\":\"inside.txt\"}"), context)).isFalse();
        assertThat(tool.needsPermission(args("{\"path\":\"" + outside + "\"}"), context)).isTrue();
        assertThat(tool.needsPermission(args("{\"path\":\"escape.txt\"}"), context)).isTrue();
    }

    @Test
    void readsCompleteTextFileWithLineNumbers() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("sample.txt"), "alpha\nbeta\ngamma\n");

        String output = tool.execute(args("{\"path\":\"sample.txt\"}"), new ToolContext(cwd));

        assertThat(output).isEqualTo("1: alpha\n2: beta\n3: gamma");
    }

    @Test
    void handlesEmptyFileAndOutOfRangeOffset() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("empty.txt"), "");
        var context = new ToolContext(cwd);

        assertThat(tool.execute(args("{\"path\":\"empty.txt\"}"), context)).isEmpty();
        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"empty.txt\",\"offset\":2}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Offset 2 is beyond end of file (0 lines total)");
    }

    @Test
    void supportsOffsetAndLimitPagination() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("lines.txt"), "l1\nl2\nl3\nl4\nl5\n");
        var context = new ToolContext(cwd);

        String middle = tool.execute(args("{\"path\":\"lines.txt\",\"offset\":2,\"limit\":2}"), context);
        assertThat(middle).isEqualTo("2: l2\n3: l3\n\n[2 more lines in file. Use offset=4 to continue.]");

        String tail = tool.execute(args("{\"path\":\"lines.txt\",\"offset\":4,\"limit\":5}"), context);
        assertThat(tail).isEqualTo("4: l4\n5: l5");

        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"lines.txt\",\"offset\":6}"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Offset 6 is beyond end of file (5 lines total)");
    }

    @Test
    void rejectsMissingFileDirectoryAndBinaryFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(cwd.resolve("dir"));
        Files.write(cwd.resolve("bin.dat"), new byte[]{0x41, 0x00, 0x42});
        var context = new ToolContext(cwd);

        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"missing.txt\"}"), context))
                .hasMessage("File not found: missing.txt");
        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"dir\"}"), context))
                .hasMessage("Path is a directory: dir");
        assertThatThrownBy(() -> tool.execute(args("{\"path\":\"bin.dat\"}"), context))
                .hasMessage("Cannot read binary file: bin.dat");
    }

    @Test
    void replacesMalformedUtf8BytesInsteadOfFailing() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.write(cwd.resolve("bad-utf8.txt"), new byte[]{(byte) 0xFF, (byte) 0xFE, '\n'});

        String output = tool.execute(args("{\"path\":\"bad-utf8.txt\"}"), new ToolContext(cwd));

        assertThat(output).startsWith("1: ").contains("\uFFFD");
    }

    @Test
    void truncatesByLineLimitAndByteLimit() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("many.txt"), "a\nb\nc\nd\n");
        var context = new ToolContext(cwd);

        ReadTool lineCapped = new ReadTool(2, 1024);
        assertThat(lineCapped.execute(args("{\"path\":\"many.txt\"}"), context))
                .isEqualTo("1: a\n2: b\n\n[Showing lines 1-2 of 4. Use offset=3 to continue.]");

        ReadTool byteCapped = new ReadTool(100, 10);
        assertThat(byteCapped.execute(args("{\"path\":\"many.txt\"}"), context))
                .isEqualTo("1: a\n2: b\n\n[Showing lines 1-2 of 4 (10B limit). Use offset=3 to continue.]");
    }

    @Test
    void reportsWhenFirstLineExceedsByteLimit() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(cwd.resolve("wide.txt"), "0123456789abcdef\nnext\n");
        var context = new ToolContext(cwd);

        ReadTool tiny = new ReadTool(100, 10);
        String output = tiny.execute(args("{\"path\":\"wide.txt\"}"), context);

        assertThat(output).isEqualTo(
                "[Line 1 is 19B, exceeds 10B limit. Use bash: sed -n '1p' wide.txt | head -c 10]");
    }

    private static JsonNode args(String json) {
        return JSON.readTree(json);
    }
}
