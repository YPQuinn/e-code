package io.agentic.pplee.ecode.core.agent;

public interface CancelToken {

    boolean isCancelled();

    Registration onCancel(Runnable action);

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
