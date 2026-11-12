package io.agentic.pplee.ecode.core.tool;

import java.nio.file.Path;

/** Per-session data that a tool needs to run a call. */
public record ToolContext(Path cwd) {
}