package io.agentic.pplee.ecode.core.agent;

public record TurnLimit(int max) {

    public TurnLimit {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be greater than 0");
        }
    }

    public static TurnLimit unlimited() {
        return new TurnLimit(Integer.MAX_VALUE);
    }

    public boolean allows(int completedTurns) {
        return completedTurns < max;
    }
}
