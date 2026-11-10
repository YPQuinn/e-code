package io.agentic.pplee.ecode.core.model;

import java.util.stream.Stream;

public interface Model {

    /**
     * Opens a blocking event stream for one model turn.
     * <p>
     * The first message of {@code request.messages()} is the system prompt.
     * The stream ends with exactly one {@link ModelEvent.Done} or {@link ModelEvent.Error}.
     * Implementations must emit {@code Error} if the connection ends without either event.
     * Closing the stream aborts the underlying request.
     */
    Stream<ModelEvent> stream(ModelRequest request);
}
