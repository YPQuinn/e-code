package io.agentic.pplee.ecode.core.model;

import java.util.stream.Stream;

public interface Model {

    /**
     * Opens a blocking event stream for one model turn.
     * <p>
     * The first message of {@code request.messages()} is the system prompt.
     * The stream ends with exactly one {@link ModelEvent.Done} or {@link ModelEvent.Error}.
     * Implementations must emit {@code Error} if the connection ends without either event.
     * Cancellation: the agent loop interrupts the thread that reads the stream.
     * The implementation must then end the stream promptly with {@link ModelEvent.Error}.
     * Closing the stream is not a reliable abort; {@code openai-java} blocks until the read times out.
     */
    Stream<ModelEvent> stream(ModelRequest request);
}
