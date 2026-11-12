package io.agentic.pplee.ecode.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnLimitTest {

    @Test
    void rejectsNonPositiveMax() {
        assertThatThrownBy(() -> new TurnLimit(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsTurnsBelowMax() {
        var limit = new TurnLimit(2);

        assertThat(limit.allows(1)).isTrue();
        assertThat(limit.allows(2)).isFalse();
    }

    @Test
    void unlimitedAllowsManyTurns() {
        assertThat(TurnLimit.unlimited().allows(1_000_000)).isTrue();
    }
}
