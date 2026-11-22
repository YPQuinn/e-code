package io.agentic.pplee.ecode.core.message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class History {

    private final List<Message> messages;

    public History(List<Message> initial) {
        this.messages = new ArrayList<>(initial);
    }

    public void append(Message message) {
        messages.add(message);
    }

    public List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public void failUnanswered(String reason) {
        int assistantIndex = lastAssistantIndex();
        if (assistantIndex < 0) {
            return;
        }
        Set<String> answered = new HashSet<>();
        for (Message message : messages.subList(assistantIndex + 1, messages.size())) {
            if (message instanceof Message.ToolResult result) {
                answered.add(result.toolCallId());
            }
        }
        var assistant = (Message.Assistant) messages.get(assistantIndex);
        for (ContentBlock.ToolCall call : assistant.toolCalls()) {
            if (!answered.contains(call.id())) {
                messages.add(Message.ToolResult.error(call, reason));
            }
        }
    }

    private int lastAssistantIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.Assistant) {
                return i;
            }
        }
        return -1;
    }
}
