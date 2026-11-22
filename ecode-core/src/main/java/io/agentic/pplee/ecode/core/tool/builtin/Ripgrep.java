package io.agentic.pplee.ecode.core.tool.builtin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class Ripgrep {

    record Execution(int exitCode, List<String> lines, String stderr) {
    }

    private static volatile Boolean cachedAvailable;

    private final String executable;

    private Ripgrep(String executable) {
        this.executable = executable;
    }

    static Optional<Ripgrep> detect() {
        Boolean available = cachedAvailable;
        if (available == null) {
            available = probe("rg");
            cachedAvailable = available;
        }
        return available ? Optional.of(new Ripgrep("rg")) : Optional.empty();
    }

    static Ripgrep of(String executable) {
        return new Ripgrep(executable);
    }

    Execution run(Path directory, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        Process process = builder.start();

        var stderrBuffer = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().start(() -> readAll(process.getErrorStream(), stderrBuffer));
        List<String> lines = new ArrayList<>();
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdout.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    destroyTree(process);
                    throw new InterruptedException();
                }
                lines.add(line);
            }
            int exitCode = process.waitFor();
            stderrReader.join();
            return new Execution(exitCode, lines, stderrBuffer.toString().trim());
        } catch (InterruptedException e) {
            destroyTree(process);
            throw e;
        } finally {
            if (process.isAlive()) {
                destroyTree(process);
            }
        }
    }

    static boolean isInsideGitRepo(Path directory) {
        Path current = directory.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static boolean probe(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                destroyTree(process);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void readAll(InputStream stream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!target.isEmpty()) {
                    target.append('\n');
                }
                target.append(line);
            }
        } catch (IOException ignored) {
        }
    }
}
