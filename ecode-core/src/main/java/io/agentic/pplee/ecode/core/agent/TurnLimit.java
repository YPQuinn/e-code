package io.agentic.pplee.ecode.core.agent;

/** How many model requests one prompt may make. */
public record TurnLimit(int max) {

    public TurnLimit {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be greater than 0");
        }
    }

    public static TurnLimit unlimited() {
        return new TurnLimit(Integer.MAX_VALUE);
    }

    /** Tells whether another model request may start after {@code completedTurns} requests. */
    public boolean allows(int completedTurns) {
        return completedTurns < max;
    }
}
