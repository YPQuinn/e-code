package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.model.Model;
import io.agentic.pplee.ecode.core.model.ModelEvent;
import io.agentic.pplee.ecode.core.model.ModelRequest;

/** Reads one model reply and forwards its deltas to the turn's events. */
final class ModelReader {

    private final Model model;
    private final TurnContext turn;

    ModelReader(Model model, TurnContext turn) {
        this.model = model;
        this.turn = turn;
    }

    Message.Assistant read() {
        var request = new ModelRequest(turn.history().snapshot(), turn.tools().definitions());
        try (var registration = turn.cancel().onCancel(Thread.currentThread()::interrupt);
             var stream = model.stream(request)) {
            for (ModelEvent event : (Iterable<ModelEvent>) stream::iterator) {
                switch (event) {
                    case ModelEvent.TextDelta e -> turn.events().textDelta(e.text());
                    case ModelEvent.ThinkingDelta e -> turn.events().thinkingDelta(e.text());
                    case ModelEvent.ToolCallDelta e -> {
                    }
                    case ModelEvent.Done e -> {
                        return e.message();
                    }
                    case ModelEvent.Error e -> throw failure(e);
                }
            }
        }
        throw new AgentException("Model stream ended without Done or Error");
    }

    private RuntimeException failure(ModelEvent.Error error) {
        if (turn.cancel().isCancelled()) {
            return new TurnCancelled();
        }
        return new AgentException(error.message());
    }
}
