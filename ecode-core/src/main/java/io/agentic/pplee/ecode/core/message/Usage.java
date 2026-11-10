package io.agentic.pplee.ecode.core.message;

public record Usage(long inputTokens,
                    long outputTokens,
                    long cacheReadTokens,
                    long cacheWriteTokens) {

    public static final Usage EMPTY = new Usage(0, 0, 0, 0);
}
