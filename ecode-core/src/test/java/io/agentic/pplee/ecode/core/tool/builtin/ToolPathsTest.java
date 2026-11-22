package io.agentic.pplee.ecode.core.tool.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativeAndNormalizedPathsAgainstCwd() {
        Path cwd = tempDir.resolve("workspace");

        assertThat(ToolPaths.resolve("src/../README.md", cwd, tempDir.resolve("home")))
                .isEqualTo(cwd.toAbsolutePath().resolve("README.md").normalize());
    }

    @Test
    void keepsAbsolutePathNormalized() {
        Path cwd = tempDir.resolve("workspace");
        Path absolute = tempDir.resolve("other/./file.txt");

        assertThat(ToolPaths.resolve(absolute.toString(), cwd, tempDir.resolve("home")))
                .isEqualTo(absolute.normalize());
    }

    @Test
    void expandsTildeOnlyForSelfAndSubpaths() {
        Path cwd = tempDir.resolve("workspace");
        Path home = tempDir.resolve("home");

        assertThat(ToolPaths.resolve("~", cwd, home)).isEqualTo(home.normalize());
        assertThat(ToolPaths.resolve("~/notes/a.txt", cwd, home)).isEqualTo(home.resolve("notes/a.txt").normalize());
        assertThat(ToolPaths.resolve("~other/a.txt", cwd, home))
                .isEqualTo(cwd.toAbsolutePath().resolve("~other/a.txt").normalize());
    }

    @Test
    void detectsPathsInsideAndOutsideCwdIncludingSiblingPrefix() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("proj"));
        Path inside = Files.writeString(cwd.resolve("a.txt"), "ok");
        Path sibling = Files.createDirectories(tempDir.resolve("proj2"));
        Path outside = Files.writeString(sibling.resolve("b.txt"), "secret");

        assertThat(ToolPaths.isWithinCwd(cwd, cwd)).isTrue();
        assertThat(ToolPaths.isWithinCwd(inside, cwd)).isTrue();
        assertThat(ToolPaths.isWithinCwd(outside, cwd)).isFalse();
    }

    @Test
    void resolvesSymlinksBeforeCheckingCwdBoundary() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path innerFile = Files.writeString(cwd.resolve("inner.txt"), "ok");
        Path outerFile = Files.writeString(tempDir.resolve("secret.txt"), "secret");
        Path innerLink = Files.createSymbolicLink(cwd.resolve("inner-link.txt"), innerFile);
        Path escapingLink = Files.createSymbolicLink(cwd.resolve("escape-link.txt"), outerFile);

        assertThat(ToolPaths.isWithinCwd(innerLink, cwd)).isTrue();
        assertThat(ToolPaths.isWithinCwd(escapingLink, cwd)).isFalse();
    }

    @Test
    void treatsMissingPlainFileInsideCwdAsWithinAndDanglingSymlinkAsOutside() throws IOException {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path missingInside = cwd.resolve("sub/missing.txt");
        Path danglingLink = Files.createSymbolicLink(cwd.resolve("dangling.txt"), tempDir.resolve("missing-target.txt"));
        Path outerDir = Files.createDirectories(tempDir.resolve("outer"));
        Path dirLink = Files.createSymbolicLink(cwd.resolve("outer-dir"), outerDir);

        assertThat(ToolPaths.isWithinCwd(missingInside, cwd)).isTrue();
        assertThat(ToolPaths.isWithinCwd(danglingLink, cwd)).isFalse();
        assertThat(ToolPaths.isWithinCwd(dirLink.resolve("new-file.txt"), cwd)).isFalse();
    }
}
