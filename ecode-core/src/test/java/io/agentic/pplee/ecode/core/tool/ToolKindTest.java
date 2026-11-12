package io.agentic.pplee.ecode.core.tool;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ToolKindTest {

    @ParameterizedTest
    @EnumSource(value = ToolKind.class, names = {"READ", "SEARCH"})
    void readAndSearchAreParallelSafe(ToolKind kind) {
        assertThat(kind.parallelSafe()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ToolKind.class, names = {"READ", "SEARCH"}, mode = EnumSource.Mode.EXCLUDE)
    void allOtherKindsAreNotParallelSafe(ToolKind kind) {
        assertThat(kind.parallelSafe()).isFalse();
    }
}
