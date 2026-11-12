package io.agentic.pplee.ecode.core.tool;

/**
 * Coarse tool category. The ACP adapter maps each value to the ACP tool kind of the same name.
 * Parallel safety is derived from the kind; there is no separate flag.
 */
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

    /** Parallel-safe calls take the read lock; all others take the write lock. */
    public boolean parallelSafe() {
        return parallelSafe;
    }
}
