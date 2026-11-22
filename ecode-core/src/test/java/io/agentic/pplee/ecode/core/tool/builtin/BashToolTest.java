package io.agentic.pplee.ecode.core.tool.builtin;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BashToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Pattern TEMP_FILE_PATTERN = Pattern.compile("Full output saved to: ([^\\] (]+)");

    @TempDir
    Path tempDir;

    private final BashTool tool = new BashTool();

    @Test
    void exposesExecuteKindAndValidatesInputSchema() {
        assertThat(tool.kind()).isEqualTo(ToolKind.EXECUTE);
        assertThat(tool.definition().name()).isEqualTo("bash");

        Schema schema = SCHEMA_REGISTRY.getSchema(tool.definition().inputSchema().toString(), InputFormat.JSON);

        assertThat(schema.validate("{\"command\":\"echo hi\"}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"command\":\"echo hi\",\"timeout\":1}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{\"command\":\"echo hi\",\"timeout\":600}", InputFormat.JSON)).isEmpty();
        assertThat(schema.validate("{}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"command\":\"\"}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"command\":\"echo hi\",\"timeout\":0}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"command\":\"echo hi\",\"timeout\":601}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema.validate("{\"command\":\"echo hi\",\"timeout\":1.5}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void formatsTitleWithoutTruncatingCommandAndAlwaysNeedsPermission() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        String longCommand = "echo " + "a".repeat(200);

        assertThat(tool.title(args("{\"command\":\"" + longCommand + "\"}"))).isEqualTo("Run " + longCommand);
        assertThat(tool.needsPermission(args("{\"command\":\"echo hi\"}"), new ToolContext(cwd))).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runsCommandInCwdMergesStderrHandlesEmptyOutputAndInjectsEnv() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        var context = new ToolContext(cwd);

        String merged = tool.execute(args("{\"command\":\"pwd && echo out && echo err >&2\"}"), context);
        assertThat(merged).contains(cwd.toRealPath().toString()).contains("out").contains("err");

        String empty = tool.execute(args("{\"command\":\"true\"}"), context);
        assertThat(empty).isEqualTo("(no output)");

        String env = tool.execute(
                args("{\"command\":\"printf '%s|%s|%s|%s|%s' \\\"$NO_COLOR\\\" \\\"$TERM\\\" \\\"$PAGER\\\" \\\"$GIT_PAGER\\\" \\\"$GIT_TERMINAL_PROMPT\\\"\"}"),
                context);
        assertThat(env).isEqualTo("1|dumb|cat|cat|0");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void throwsWithCapturedOutputOnNonZeroExitCode() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));

        assertThatThrownBy(() -> tool.execute(args("{\"command\":\"echo failure-detail >&2; exit 7\"}"), new ToolContext(cwd)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failure-detail\n\nCommand exited with code 7");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void terminatesProcessTreeOnTimeout() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path pidFile = cwd.resolve("child.pid");
        BashTool fastKill = new BashTool(120, 2000, 50 * 1024, BashTool.DEFAULT_MAX_SPILL_BYTES, 200L, BashTool.Shell.detect());

        assertThatThrownBy(() -> fastKill.execute(
                args("{\"command\":\"sleep 30 & echo $! > child.pid; wait\",\"timeout\":1}"),
                new ToolContext(cwd)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Command timed out after 1 seconds");

        long childPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
        awaitProcessExit(childPid);
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void terminatesProcessTreeWhenInterrupted() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path pidFile = cwd.resolve("child.pid");
        BashTool fastKill = new BashTool(120, 2000, 50 * 1024, BashTool.DEFAULT_MAX_SPILL_BYTES, 200L, BashTool.Shell.detect());

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                fastKill.execute(
                        args("{\"command\":\"sleep 30 & echo $! > child.pid; wait\",\"timeout\":30}"),
                        new ToolContext(cwd));
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                finished.countDown();
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(pidFile) || Files.size(pidFile) == 0) {
            if (System.nanoTime() > deadline) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(Files.exists(pidFile)).isTrue();
        long childPid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());

        worker.interrupt();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        awaitProcessExit(childPid);
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void truncatesTailByLinesAndBytesAndCapsSpillFile() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        var context = new ToolContext(cwd);

        BashTool lineCapped = new BashTool(120, 2, 50 * 1024, BashTool.DEFAULT_MAX_SPILL_BYTES, 200L, BashTool.Shell.detect());
        String byLines = lineCapped.execute(args("{\"command\":\"printf 'l1\\nl2\\nl3\\nl4\\n'\"}"), context);
        Path lineTemp = extractTempFile(byLines);
        try {
            assertThat(byLines).startsWith("l3\nl4\n\n[Showing last 2 of 4 lines. Full output saved to: ");
            assertThat(Files.readString(lineTemp, StandardCharsets.UTF_8)).isEqualTo("l1\nl2\nl3\nl4\n");
        } finally {
            Files.deleteIfExists(lineTemp);
        }

        BashTool spillCapped = new BashTool(120, 2000, 12, 20L, 200L, BashTool.Shell.detect());
        String byBytes = spillCapped.execute(args("{\"command\":\"printf 'aaaa1\\nbbbb2\\ncccc3\\ndddd4\\neeee5\\n'\"}"), context);
        Path spillTemp = extractTempFile(byBytes);
        try {
            assertThat(byBytes)
                    .startsWith("dddd4\neeee5\n\n[Showing last 2 of 5 lines (12B limit). Full output saved to: ")
                    .contains("(temp file capped at 20B)");
            assertThat(Files.size(spillTemp)).isEqualTo(20L);
        } finally {
            Files.deleteIfExists(spillTemp);
        }
    }

    private static void awaitProcessExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(20);
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
