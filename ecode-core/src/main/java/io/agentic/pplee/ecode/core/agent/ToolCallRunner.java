package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.message.StopReason;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class ToolCallRunner {

    private static final String CANCELED = "Canceled by the user";

    private final TurnContext turn;
    private final ArgumentValidator validator = new ArgumentValidator();
    private final ReentrantLock eventLock = new ReentrantLock();

    ToolCallRunner(TurnContext turn) {
        this.turn = turn;
    }

    void runAll(Message.Assistant reply) {
        List<ContentBlock.ToolCall> calls = reply.toolCalls();
        Message.ToolResult[] results = new Message.ToolResult[calls.size()];
        boolean truncated = reply.stopReason() == StopReason.LENGTH;
        boolean cancelled = false;

        try {
            List<PendingCall> pending = prepareAll(calls, results, truncated);
            cancelled = executeAll(pending, results);
        } catch (TurnCancelled e) {
            cancelled = true;
        }

        for (int i = 0; i < calls.size(); i++) {
            Message.ToolResult result = results[i];
            turn.history().append(result != null ? result : Message.ToolResult.error(calls.get(i), CANCELED));
        }
        if (cancelled) {
            throw new TurnCancelled();
        }
    }

    private List<PendingCall> prepareAll(List<ContentBlock.ToolCall> calls,
                                         Message.ToolResult[] results,
                                         boolean truncated) {
        List<PendingCall> pending = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (turn.cancel().isCancelled()) {
                throw new TurnCancelled();
            }
            ContentBlock.ToolCall call = calls.get(i);
            Optional<Tool> found = turn.tools().find(call.name());
            if (found.isEmpty()) {
                results[i] = reject(call, ToolKind.OTHER, "Unknown tool: " + call.name());
                continue;
            }
            Tool tool = found.get();
            if (truncated) {
                results[i] = reject(call, tool.kind(), "Response was cut off before the call completed");
                continue;
            }
            Optional<String> validationError = validator.validate(tool.definition().inputSchema(), call.arguments());
            if (validationError.isPresent()) {
                results[i] = reject(call, tool.kind(), validationError.get());
                continue;
            }
            String title = tool.title(call.arguments());
            turn.events().toolCallStarted(call, tool.kind(), title);
            if (tool.needsPermission(call.arguments(), turn.toolContext())) {
                switch (turn.gate().request(new PermissionRequest(call, tool.kind(), title))) {
                    case REJECT -> {
                        results[i] = finish(Message.ToolResult.error(call, "Permission denied by the user"));
                        continue;
                    }
                    case CANCEL -> throw new TurnCancelled();
                    case ALLOW -> {
                    }
                }
            }
            pending.add(new PendingCall(i, call, tool));
        }
        return pending;
    }

    private boolean executeAll(List<PendingCall> pending, Message.ToolResult[] results) {
        if (pending.isEmpty()) {
            return false;
        }
        var rwLock = new ReentrantReadWriteLock(true);
        List<SubmittedCall> submitted = new ArrayList<>(pending.size());
        boolean cancelled = false;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PendingCall item : pending) {
                Future<Message.ToolResult> future = executor.submit(
                        () -> invoke(item.call(), item.tool(), rwLock));
                submitted.add(new SubmittedCall(item.index(), future));
            }
            for (SubmittedCall item : submitted) {
                try {
                    results[item.index()] = item.future().get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof TurnCancelled) {
                        cancelled = true;
                    } else if (cause instanceof Error err) {
                        throw err;
                    } else if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    } else {
                        throw new IllegalStateException("Unexpected checked exception from tool runner", cause);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    submitted.forEach(s -> s.future().cancel(true));
                    cancelled = true;
                    break;
                }
            }
        }
        return cancelled;
    }

    private Message.ToolResult reject(ContentBlock.ToolCall call, ToolKind kind, String reason) {
        turn.events().toolCallStarted(call, kind, call.name());
        return finish(Message.ToolResult.error(call, reason));
    }

    private Message.ToolResult invoke(ContentBlock.ToolCall call, Tool tool, ReentrantReadWriteLock rwLock) {
        Lock lock = tool.kind().parallelSafe() ? rwLock.readLock() : rwLock.writeLock();
        Message.ToolResult result;
        try (var ignored = turn.cancel().onCancel(Thread.currentThread()::interrupt)) {
            lock.lockInterruptibly();
            try {
                if (turn.cancel().isCancelled()) {
                    throw new TurnCancelled();
                }
                String text = tool.execute(call.arguments(), turn.toolContext());
                result = new Message.ToolResult(call.id(), call.name(), text, false);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (turn.cancel().isCancelled()) {
                throw new TurnCancelled();
            }
            result = Message.ToolResult.error(call, errorMessage(e));
        } catch (TurnCancelled e) {
            throw e;
        } catch (Exception e) {
            if (turn.cancel().isCancelled()) {
                throw new TurnCancelled();
            }
            result = Message.ToolResult.error(call, errorMessage(e));
        }
        return finish(result);
    }

    private Message.ToolResult finish(Message.ToolResult result) {
        eventLock.lock();
        try {
            turn.events().toolCallFinished(result);
            return result;
        } finally {
            eventLock.unlock();
        }
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private record PendingCall(int index, ContentBlock.ToolCall call, Tool tool) {}

    private record SubmittedCall(int index, Future<Message.ToolResult> future) {}
}
