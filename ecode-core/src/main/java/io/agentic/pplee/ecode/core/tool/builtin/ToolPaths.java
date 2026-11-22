package io.agentic.pplee.ecode.core.tool.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

final class ToolPaths {

    private ToolPaths() {
    }

    static Path resolve(String rawPath, Path cwd) {
        return resolve(rawPath, cwd, Path.of(System.getProperty("user.home")));
    }

    static Path resolve(String rawPath, Path cwd, Path homeDir) {
        Path expanded = expandTilde(rawPath, homeDir);
        if (expanded.isAbsolute()) {
            return expanded.normalize();
        }
        return cwd.toAbsolutePath().resolve(expanded).normalize();
    }

    static boolean isWithinCwd(Path target, Path cwd) {
        try {
            Path realCwd = cwd.toAbsolutePath().normalize().toRealPath();
            Path realTarget = resolveRealOrMissing(target.toAbsolutePath().normalize());
            return realTarget != null && realTarget.startsWith(realCwd);
        } catch (IOException e) {
            return false;
        }
    }

    private static Path expandTilde(String rawPath, Path homeDir) {
        if (rawPath.equals("~")) {
            return homeDir;
        }
        if (rawPath.startsWith("~/") || rawPath.startsWith("~\\")) {
            return homeDir.resolve(rawPath.substring(2));
        }
        return Path.of(rawPath);
    }

    private static Path resolveRealOrMissing(Path normalized) throws IOException {
        if (Files.exists(normalized)) {
            return normalized.toRealPath();
        }
        if (Files.isSymbolicLink(normalized)) {
            return null;
        }
        Deque<Path> trailing = new ArrayDeque<>();
        Path current = normalized;
        while (current != null && !Files.exists(current)) {
            if (Files.isSymbolicLink(current)) {
                return null;
            }
            Path name = current.getFileName();
            if (name == null) {
                return null;
            }
            trailing.push(name);
            current = current.getParent();
        }
        if (current == null) {
            return null;
        }
        Path resolved = current.toRealPath();
        while (!trailing.isEmpty()) {
            resolved = resolved.resolve(trailing.pop());
        }
        return resolved.normalize();
    }
}
