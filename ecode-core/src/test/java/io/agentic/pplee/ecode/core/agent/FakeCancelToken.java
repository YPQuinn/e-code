package io.agentic.pplee.ecode.core.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class FakeCancelToken implements CancelToken {

    private final List<Runnable> actions = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
        actions.forEach(Runnable::run);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Registration onCancel(Runnable action) {
        actions.add(action);
        if (cancelled) {
            action.run();
        }
        return () -> actions.remove(action);
    }
}
