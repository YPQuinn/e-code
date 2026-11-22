package io.agentic.pplee.ecode.core.tool.builtin;

import io.agentic.pplee.ecode.core.model.ToolDefinition;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;
import io.agentic.pplee.ecode.core.tool.ToolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class BashTool implements Tool {

    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_TIMEOUT_SECONDS = 600;
    static final long DEFAULT_MAX_SPILL_BYTES = 64L * 1024 * 1024;
    static final long DEFAULT_GRACE_MILLIS = 1_000L;

    private static final int READ_BUFFER_BYTES = 8 * 1024;
    private static final long READER_JOIN_MILLIS = 1_000L;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "bash",
            "Execute a shell command in the current working directory. Each call runs in a fresh shell; working directory changes and environment variables do not persist across calls. Prefer dedicated tools (read, write, edit, ls, find, grep) for file operations and search. Output is truncated to the last 2000 lines or 50KB (whichever is hit first), with full output saved to a temp file. Default timeout is 120 seconds (max 600).",
            JSON.readTree("""
                    {
                      "type": "object",
                      "properties": {
                        "command": {
                          "type": "string",
                          "minLength": 1,
                          "description": "Shell command to execute"
                        },
                        "timeout": {
                          "type": "integer",
                          "minimum": 1,
                          "maximum": 600,
                          "description": "Timeout in seconds (default: 120, max 600)"
                        }
                      },
                      "required": ["command"]
                    }
                    """));

    private final int defaultTimeoutSeconds;
    private final int maxLines;
    private final int maxBytes;
    private final long maxSpillBytes;
    private final long graceMillis;
    private final Shell shell;

    public BashTool() {
        this(
                DEFAULT_TIMEOUT_SECONDS,
                OutputTruncation.DEFAULT_MAX_LINES,
                OutputTruncation.DEFAULT_MAX_BYTES,
                DEFAULT_MAX_SPILL_BYTES,
                DEFAULT_GRACE_MILLIS,
                Shell.detect());
    }

    BashTool(
            int defaultTimeoutSeconds,
            int maxLines,
            int maxBytes,
            long maxSpillBytes,
            long graceMillis,
            Shell shell) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
        this.maxSpillBytes = maxSpillBytes;
        this.graceMillis = graceMillis;
        this.shell = shell;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolKind kind() {
        return ToolKind.EXECUTE;
    }

    @Override
    public String title(JsonNode arguments) {
        return "Run " + arguments.path("command").asString("");
    }

    @Override
    public String execute(JsonNode arguments, ToolContext context) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        String command = arguments.path("command").asString("");
        int timeoutSeconds = optionalInt(arguments, "timeout", defaultTimeoutSeconds);
        Path cwd = context.cwd().toAbsolutePath().normalize();
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("Working directory does not exist: " + cwd);
        }

        ProcessBuilder builder = new ProcessBuilder(shell.command(command));
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        applyNonInteractiveEnv(builder.environment());

        Process process = builder.start();
        process.getOutputStream().close();

        OutputCapture capture = new OutputCapture(maxBytes, maxSpillBytes, shell.charset());
        Thread reader = Thread.ofVirtual().start(() -> capture.drain(process.getInputStream()));

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                terminateTree(process, graceMillis);
                reader.join(READER_JOIN_MILLIS);
                capture.close();
                String output = formatOutput(capture, "");
                throw new IllegalStateException(
                        appendStatus(output, "Command timed out after " + timeoutSeconds + " seconds"));
            }
            reader.join(READER_JOIN_MILLIS);
            capture.close();
            int exitCode = process.exitValue();
            String output = formatOutput(capture, "(no output)");
            if (exitCode != 0) {
                throw new IllegalStateException(appendStatus(output, "Command exited with code " + exitCode));
            }
            return output;
        } catch (InterruptedException e) {
            terminateTree(process, graceMillis);
            joinUninterruptibly(reader, READER_JOIN_MILLIS);
            capture.close();
            capture.deleteTempFile();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) {
                terminateTree(process, 0);
            }
            capture.close();
        }
    }

    private String formatOutput(OutputCapture capture, String emptyText) throws IOException {
        String tailText = capture.tailText();
        OutputTruncation.Result truncation = OutputTruncation.truncateTail(tailText, maxLines, maxBytes);
        boolean truncated = capture.wasRolled() || truncation.truncated();
        if (!truncated) {
            return truncation.content().isEmpty() ? emptyText : truncation.content();
        }

        Path tempFile = capture.ensureTempFile(tailText);
        long totalLines = capture.wasRolled() ? capture.totalLines() : truncation.totalLines();
        String notice = buildTruncationNotice(truncation, totalLines, tempFile, capture.spillCapped());
        if (truncation.content().isEmpty()) {
            return "[" + notice + "]";
        }
        return truncation.content() + "\n\n[" + notice + "]";
    }

    private String buildTruncationNotice(
            OutputTruncation.Result truncation,
            long totalLines,
            Path tempFile,
            boolean spillCapped) {
        String capSuffix = spillCapped
                ? " (temp file capped at " + OutputTruncation.formatSize((int) Math.min(maxSpillBytes, Integer.MAX_VALUE)) + ")"
                : "";
        if (truncation.lastLinePartial()) {
            return "Showing last " + OutputTruncation.formatSize(truncation.outputBytes())
                    + " of line " + totalLines + ". Full output saved to: " + tempFile + capSuffix;
        }
        if (truncation.reason() == OutputTruncation.Reason.BYTES || !truncation.truncated()) {
            return "Showing last " + truncation.outputLines() + " of " + totalLines
                    + " lines (" + OutputTruncation.formatSize(maxBytes) + " limit). Full output saved to: "
                    + tempFile + capSuffix;
        }
        return "Showing last " + truncation.outputLines() + " of " + totalLines
                + " lines. Full output saved to: " + tempFile + capSuffix;
    }

    private static void applyNonInteractiveEnv(Map<String, String> env) {
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        env.put("PAGER", "cat");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_TERMINAL_PROMPT", "0");
    }

    private static void terminateTree(Process process, long graceMillis) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (graceMillis > 0) {
            try {
                process.waitFor(graceMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinUninterruptibly(Thread thread, long timeoutMillis) {
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String appendStatus(String output, String status) {
        if (output.isEmpty()) {
            return status;
        }
        String trimmed = output.endsWith("\n") ? output.substring(0, output.length() - 1) : output;
        return trimmed + "\n\n" + status;
    }

    private static int optionalInt(JsonNode arguments, String field, int fallback) {
        JsonNode node = arguments.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.intValue();
    }

    record Shell(List<String> prefix, Charset charset) {

        List<String> command(String script) {
            List<String> argv = new ArrayList<>(prefix.size() + 1);
            argv.addAll(prefix);
            argv.add(script);
            return argv;
        }

        static Shell detect() {
            if (isWindows()) {
                Path gitBash = findWindowsBash();
                if (gitBash != null) {
                    return new Shell(List.of(gitBash.toString(), "-c"), StandardCharsets.UTF_8);
                }
                return new Shell(List.of("cmd.exe", "/c"), nativeCharset());
            }
            if (Files.isExecutable(Path.of("/bin/bash"))) {
                return new Shell(List.of("/bin/bash", "-c"), StandardCharsets.UTF_8);
            }
            return new Shell(List.of("sh", "-c"), StandardCharsets.UTF_8);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }

        private static Path findWindowsBash() {
            List<String> candidates = new ArrayList<>();
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null && !programFiles.isEmpty()) {
                candidates.add(programFiles + "\\Git\\bin\\bash.exe");
            }
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            if (programFilesX86 != null && !programFilesX86.isEmpty()) {
                candidates.add(programFilesX86 + "\\Git\\bin\\bash.exe");
            }
            for (String candidate : candidates) {
                Path path = Path.of(candidate);
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null || pathEnv.isEmpty()) {
                return null;
            }
            for (String entry : pathEnv.split(File.pathSeparator)) {
                if (entry.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(entry, "bash.exe");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private static Charset nativeCharset() {
            try {
                return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    private static final class OutputCapture {

        private final ReentrantLock lock = new ReentrantLock();
        private final int maxBytes;
        private final int tailKeepBytes;
        private final long maxSpillBytes;
        private final Charset charset;

        private ByteArrayOutputStream tailBuffer = new ByteArrayOutputStream();
        private Path tempFile;
        private OutputStream tempOut;
        private long totalBytes;
        private long spilledBytes;
        private long newlineCount;
        private byte lastByte;
        private boolean rolled;
        private boolean spillCapped;

        OutputCapture(int maxBytes, long maxSpillBytes, Charset charset) {
            this.maxBytes = maxBytes;
            this.tailKeepBytes = Math.max(maxBytes * 2, 1024);
            this.maxSpillBytes = maxSpillBytes;
            this.charset = charset;
        }

        void drain(InputStream input) {
            byte[] chunk = new byte[READ_BUFFER_BYTES];
            try (input) {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    if (read > 0) {
                        append(chunk, read);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        private void append(byte[] chunk, int length) {
            lock.lock();
            try {
                for (int i = 0; i < length; i++) {
                    if (chunk[i] == '\n') {
                        newlineCount++;
                    }
                }
                lastByte = chunk[length - 1];
                totalBytes += length;

                if (tempOut == null && totalBytes > maxBytes) {
                    openTempFile();
                }
                if (tempOut != null && !spillCapped) {
                    writeSpill(chunk, length);
                }

                tailBuffer.write(chunk, 0, length);
                if (tailBuffer.size() > tailKeepBytes * 2) {
                    byte[] existing = tailBuffer.toByteArray();
                    int keepStart = existing.length - tailKeepBytes;
                    ByteArrayOutputStream trimmed = new ByteArrayOutputStream(tailKeepBytes);
                    trimmed.write(existing, keepStart, existing.length - keepStart);
                    tailBuffer = trimmed;
                    rolled = true;
                }
            } finally {
                lock.unlock();
            }
        }

        private void openTempFile() {
            try {
                tempFile = Files.createTempFile("ecode-bash-", ".log");
                tempOut = Files.newOutputStream(tempFile);
                byte[] buffered = tailBuffer.toByteArray();
                if (buffered.length > 0) {
                    writeSpill(buffered, buffered.length);
                }
            } catch (IOException e) {
                spillCapped = true;
            }
        }

        private void writeSpill(byte[] data, int length) {
            long remaining = maxSpillBytes - spilledBytes;
            if (remaining <= 0) {
                spillCapped = true;
                return;
            }
            int toWrite = (int) Math.min(length, remaining);
            try {
                tempOut.write(data, 0, toWrite);
                spilledBytes += toWrite;
                if (toWrite < length) {
                    spillCapped = true;
                }
            } catch (IOException e) {
                spillCapped = true;
            }
        }

        String tailText() {
            lock.lock();
            try {
                byte[] bytes = tailBuffer.toByteArray();
                int start = 0;
                if (rolled && StandardCharsets.UTF_8.equals(charset)) {
                    while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
                        start++;
                    }
                }
                return decodeLossy(bytes, start, bytes.length - start, charset).replace("\r\n", "\n");
            } finally {
                lock.unlock();
            }
        }

        Path ensureTempFile(String fallbackContent) throws IOException {
            lock.lock();
            try {
                if (tempFile != null) {
                    return tempFile;
                }
                tempFile = OutputTruncation.writeTempFile("bash", fallbackContent);
                return tempFile;
            } finally {
                lock.unlock();
            }
        }

        long totalLines() {
            lock.lock();
            try {
                if (totalBytes == 0) {
                    return 0;
                }
                return lastByte == '\n' ? newlineCount : newlineCount + 1;
            } finally {
                lock.unlock();
            }
        }

        boolean wasRolled() {
            lock.lock();
            try {
                return rolled;
            } finally {
                lock.unlock();
            }
        }

        boolean spillCapped() {
            lock.lock();
            try {
                return spillCapped;
            } finally {
                lock.unlock();
            }
        }

        void close() {
            lock.lock();
            try {
                if (tempOut != null) {
                    try {
                        tempOut.close();
                    } catch (IOException ignored) {
                    }
                    tempOut = null;
                }
            } finally {
                lock.unlock();
            }
        }

        void deleteTempFile() {
            lock.lock();
            try {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                    }
                    tempFile = null;
                }
            } finally {
                lock.unlock();
            }
        }

        private static String decodeLossy(byte[] bytes, int offset, int length, Charset charset) {
            try {
                return charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes, offset, length))
                        .toString();
            } catch (CharacterCodingException e) {
                return new String(bytes, offset, length, charset);
            }
        }
    }
}
