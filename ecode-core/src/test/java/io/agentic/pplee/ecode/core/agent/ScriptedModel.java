package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.model.Model;
import io.agentic.pplee.ecode.core.model.ModelEvent;
import io.agentic.pplee.ecode.core.model.ModelRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/** A model that plays back one prepared event list per call and records every request. */
final class ScriptedModel implements Model {

    private final Deque<List<ModelEvent>> script = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    ScriptedModel reply(Message.Assistant message) {
        return emit(new ModelEvent.Done(message));
    }

    ScriptedModel fail(String message) {
        return emit(new ModelEvent.Error(message));
    }

    ScriptedModel emit(ModelEvent... events) {
        script.add(List.of(events));
        return this;
    }

    List<ModelRequest> requests() {
        return requests;
    }

    @Override
    public Stream<ModelEvent> stream(ModelRequest request) {
        requests.add(request);
        return script.removeFirst().stream();
    }
}
