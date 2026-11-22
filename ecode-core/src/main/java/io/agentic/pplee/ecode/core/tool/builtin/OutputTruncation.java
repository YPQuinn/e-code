package io.agentic.pplee.ecode.core.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class OutputTruncation {

    static final int DEFAULT_MAX_LINES = 2000;
    static final int DEFAULT_MAX_BYTES = 50 * 1024;

    enum Reason {
        NONE,
        LINES,
        BYTES
    }

    record Result(
            String content,
            boolean truncated,
            Reason reason,
            int totalLines,
            int totalBytes,
            int outputLines,
            int outputBytes,
            boolean firstLineExceedsLimit,
            boolean lastLinePartial) {
    }

    private OutputTruncation() {
    }

    static Result truncateHead(String content) {
        return truncateHead(content, DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }

    static Result truncateHead(String content, int maxLines, int maxBytes) {
        int totalBytes = utf8Length(content);
        List<String> lines = splitLines(content);
        int totalLines = lines.size();

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new Result(content, false, Reason.NONE, totalLines, totalBytes, totalLines, totalBytes, false, false);
        }

        if (utf8Length(lines.getFirst()) > maxBytes) {
            return new Result("", true, Reason.BYTES, totalLines, totalBytes, 0, 0, true, false);
        }

        List<String> kept = new ArrayList<>();
        int outputBytes = 0;
        Reason reason = Reason.LINES;

        for (int i = 0; i < totalLines && i < maxLines; i++) {
            String line = lines.get(i);
            int lineBytes = utf8Length(line) + (i > 0 ? 1 : 0);
            if (outputBytes + lineBytes > maxBytes) {
                reason = Reason.BYTES;
                break;
            }
            kept.add(line);
            outputBytes += lineBytes;
        }

        String truncated = String.join("\n", kept);
        return new Result(truncated, true, reason, totalLines, totalBytes, kept.size(), utf8Length(truncated), false, false);
    }

    static Result truncateTail(String content) {
        return truncateTail(content, DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }

    static Result truncateTail(String content, int maxLines, int maxBytes) {
        int totalBytes = utf8Length(content);
        List<String> lines = splitLines(content);
        int totalLines = lines.size();

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new Result(content, false, Reason.NONE, totalLines, totalBytes, totalLines, totalBytes, false, false);
        }

        List<String> kept = new ArrayList<>();
        int outputBytes = 0;
        Reason reason = Reason.LINES;
        boolean lastLinePartial = false;

        for (int i = totalLines - 1; i >= 0 && kept.size() < maxLines; i--) {
            String line = lines.get(i);
            int lineBytes = utf8Length(line) + (kept.isEmpty() ? 0 : 1);
            if (outputBytes + lineBytes > maxBytes) {
                reason = Reason.BYTES;
                if (kept.isEmpty()) {
                    String partial = tailByUtf8Bytes(line, maxBytes);
                    kept.add(partial);
                    outputBytes = utf8Length(partial);
                    lastLinePartial = true;
                }
                break;
            }
            kept.add(line);
            outputBytes += lineBytes;
        }

        Collections.reverse(kept);
        String truncated = String.join("\n", kept);
        return new Result(truncated, true, reason, totalLines, totalBytes, kept.size(), utf8Length(truncated), false, lastLinePartial);
    }

    static Path writeTempFile(String prefix, String fullContent) throws IOException {
        Path file = Files.createTempFile("ecode-" + prefix + "-", ".log");
        return Files.writeString(file, fullContent, StandardCharsets.UTF_8);
    }

    static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String trimmed = content.endsWith("\n")
                ? content.substring(0, content.length() - 1)
                : content;
        return List.of(trimmed.split("\n", -1));
    }

    private static String tailByUtf8Bytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }
}
