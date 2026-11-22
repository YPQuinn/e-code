package io.agentic.pplee.ecode.core.tool;

public enum ToolKind {
    READ(true),
    SEARCH(true),
    EDIT(false),
    EXECUTE(false),
    OTHER(false);

    private final boolean parallelSafe;

    ToolKind(boolean parallelSafe) {
        this.parallelSafe = parallelSafe;
    }

    public boolean parallelSafe() {
        return parallelSafe;
    }
}
