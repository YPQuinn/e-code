package io.agentic.pplee.ecode.core.agent;

/**
 * Tells the agent loop that the user canceled the running turn.
 * The loop registers an action that interrupts the virtual thread blocked on model or tool I/O.
 */
public interface CancelToken {

    boolean isCancelled();

    /** Runs the action on cancel, or at once if already cancelled. Closing the handle removes the action. */
    Registration onCancel(Runnable action);

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
