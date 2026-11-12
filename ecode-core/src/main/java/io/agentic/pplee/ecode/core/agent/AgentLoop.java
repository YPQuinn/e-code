package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.message.StopReason;
import io.agentic.pplee.ecode.core.model.Model;

/**
 * Drives one prompt: asks the model, runs its tool calls, and repeats until the model stops.
 * Stateless and thread-safe. All session data arrives in the {@link TurnContext}.
 */
public final class AgentLoop {

    private final Model model;
    private final TurnLimit limit;

    public AgentLoop(Model model, TurnLimit limit) {
        this.model = model;
        this.limit = limit;
    }

    /**
     * Runs model turns until the model stops. Appends every new message to the turn's history.
     *
     * @throws AgentException when the model call fails
     */
    public TurnOutcome run(TurnContext turn) {
        var reader = new ModelReader(model, turn);
        var runner = new ToolCallRunner(turn);
        try {
            for (int completed = 0; limit.allows(completed); completed++) {
                Message.Assistant reply = reader.read();
                turn.history().append(reply);
                if (reply.toolCalls().isEmpty()) {
                    return outcomeOf(reply);
                }
                runner.runAll(reply);
            }
            return TurnOutcome.MAX_TURN_REQUESTS;
        } catch (TurnCancelled e) {
            return TurnOutcome.CANCELLED;
        }
    }

    private static TurnOutcome outcomeOf(Message.Assistant reply) {
        return reply.stopReason() == StopReason.LENGTH ? TurnOutcome.MAX_TOKENS : TurnOutcome.END_TURN;
    }
}
