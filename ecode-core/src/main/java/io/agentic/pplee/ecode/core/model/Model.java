package io.agentic.pplee.ecode.core.model;

import java.util.stream.Stream;

public interface Model {

    Stream<ModelEvent> stream(ModelRequest request);
}
