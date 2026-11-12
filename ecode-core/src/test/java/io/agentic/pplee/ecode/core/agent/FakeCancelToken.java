package io.agentic.pplee.ecode.core.agent;

/** A token that the test cancels by hand. It never runs registered actions. */
final class FakeCancelToken implements CancelToken {

    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Registration onCancel(Runnable action) {
        return () -> {
        };
    }
}
