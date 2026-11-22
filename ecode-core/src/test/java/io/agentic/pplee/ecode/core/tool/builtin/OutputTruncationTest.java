package io.agentic.pplee.ecode.core.tool.builtin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OutputTruncationTest {

    @Test
    void returnsOriginalContentWhenWithinLimits() {
        String text = "line1\nline2\n";

        OutputTruncation.Result result = OutputTruncation.truncateHead(text, 10, 100);

        assertThat(result.truncated()).isFalse();
        assertThat(result.reason()).isEqualTo(OutputTruncation.Reason.NONE);
        assertThat(result.content()).isEqualTo(text);
        assertThat(result.totalLines()).isEqualTo(2);
        assertThat(result.outputLines()).isEqualTo(2);
    }

    @Test
    void truncatesHeadByLineLimit() {
        String text = "a\nb\nc\nd\n";

        OutputTruncation.Result result = OutputTruncation.truncateHead(text, 2, 100);

        assertThat(result.truncated()).isTrue();
        assertThat(result.reason()).isEqualTo(OutputTruncation.Reason.LINES);
        assertThat(result.content()).isEqualTo("a\nb");
        assertThat(result.totalLines()).isEqualTo(4);
        assertThat(result.outputLines()).isEqualTo(2);
    }

    @Test
    void truncatesHeadByByteLimitOnWholeLineBoundary() {
        String text = "12345\n67890\nabcde";

        OutputTruncation.Result result = OutputTruncation.truncateHead(text, 10, 11);

        assertThat(result.truncated()).isTrue();
        assertThat(result.reason()).isEqualTo(OutputTruncation.Reason.BYTES);
        assertThat(result.content()).isEqualTo("12345\n67890");
        assertThat(result.outputLines()).isEqualTo(2);
    }

    @Test
    void flagsFirstLineExceedsLimitInHeadTruncation() {
        String text = "1234567890\nshort";

        OutputTruncation.Result result = OutputTruncation.truncateHead(text, 10, 5);

        assertThat(result.truncated()).isTrue();
        assertThat(result.reason()).isEqualTo(OutputTruncation.Reason.BYTES);
        assertThat(result.firstLineExceedsLimit()).isTrue();
        assertThat(result.content()).isEmpty();
        assertThat(result.outputLines()).isZero();
    }

    @Test
    void truncatesTailByLineAndByteLimits() {
        String text = "first\nsecond\nthird\nfourth";

        OutputTruncation.Result byLines = OutputTruncation.truncateTail(text, 2, 100);
        assertThat(byLines.truncated()).isTrue();
        assertThat(byLines.reason()).isEqualTo(OutputTruncation.Reason.LINES);
        assertThat(byLines.content()).isEqualTo("third\nfourth");

        OutputTruncation.Result byBytes = OutputTruncation.truncateTail(text, 10, 12);
        assertThat(byBytes.truncated()).isTrue();
        assertThat(byBytes.reason()).isEqualTo(OutputTruncation.Reason.BYTES);
        assertThat(byBytes.content()).isEqualTo("third\nfourth");
        assertThat(byBytes.lastLinePartial()).isFalse();
    }

    @Test
    void truncatesOversizedLastLineOnUtf8Boundary() {
        String text = "header\n你好世界";

        OutputTruncation.Result result = OutputTruncation.truncateTail(text, 10, 7);

        assertThat(result.truncated()).isTrue();
        assertThat(result.reason()).isEqualTo(OutputTruncation.Reason.BYTES);
        assertThat(result.lastLinePartial()).isTrue();
        assertThat(result.content()).isEqualTo("世界");
        assertThat(result.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(7);
    }

    @Test
    void writesFullOutputToTempFile() throws IOException {
        Path temp = OutputTruncation.writeTempFile("bash", "hello\n世界");
        try {
            assertThat(Files.readString(temp, StandardCharsets.UTF_8)).isEqualTo("hello\n世界");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void formatsByteSizes() {
        assertThat(OutputTruncation.formatSize(512)).isEqualTo("512B");
        assertThat(OutputTruncation.formatSize(50 * 1024)).isEqualTo("50.0KB");
        assertThat(OutputTruncation.formatSize(2 * 1024 * 1024)).isEqualTo("2.0MB");
    }
}
