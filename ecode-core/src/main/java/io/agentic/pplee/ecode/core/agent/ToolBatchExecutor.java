package io.agentic.pplee.ecode.core.agent;

import io.agentic.pplee.ecode.core.message.ContentBlock;
import io.agentic.pplee.ecode.core.message.Message;
import io.agentic.pplee.ecode.core.tool.Tool;
import io.agentic.pplee.ecode.core.tool.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

final class ToolBatchExecutor {

    sealed interface Task {
        record Ready(ContentBlock.ToolCall call, Tool tool) implements Task {}
        record AlreadyDone(Message.ToolResult result) implements Task {}
    }

    sealed interface Outcome {
        record Finished(Message.ToolResult result) implements Outcome {}
        record Cancelled() implements Outcome {}
    }

    private sealed interface Slot {
        record Immediate(Message.ToolResult result) implements Slot {}
        record Running(Future<Outcome> future) implements Slot {}
    }

    private final ToolContext toolContext;
    private final CancelToken cancel;
    private final Consumer<Message.ToolResult> onFinished;
    private final ReentrantLock eventLock = new ReentrantLock();

    ToolBatchExecutor(ToolContext toolContext, CancelToken cancel, Consumer<Message.ToolResult> onFinished) {
        this.toolContext = toolContext;
        this.cancel = cancel;
        this.onFinished = onFinished;
    }

    List<Outcome> executeAll(List<Task> tasks) {
        if (tasks.stream().noneMatch(Task.Ready.class::isInstance)) {
            return tasks.stream()
                    .map(task -> (Outcome) new Outcome.Finished(((Task.AlreadyDone) task).result()))
                    .toList();
        }
        var rwLock = new ReentrantReadWriteLock(true);
        List<Slot> slots = new ArrayList<>(tasks.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Task task : tasks) {
                slots.add(submit(task, executor, rwLock));
            }
            return collectAll(slots);
        }
    }

    private Slot submit(Task task, ExecutorService executor, ReentrantReadWriteLock rwLock) {
        return switch (task) {
            case Task.AlreadyDone done -> new Slot.Immediate(done.result());
            case Task.Ready ready -> new Slot.Running(executor.submit(() -> invoke(ready, rwLock)));
        };
    }

    private List<Outcome> collectAll(List<Slot> slots) {
        List<Outcome> outcomes = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            switch (slot) {
                case Slot.Immediate immediate -> outcomes.add(new Outcome.Finished(immediate.result()));
                case Slot.Running running -> outcomes.add(await(running.future(), slots));
            }
        }
        return outcomes;
    }

    private Outcome await(Future<Outcome> future, List<Slot> allSlots) {
        try {
            return future.get();
        } catch (CancellationException e) {
            return new Outcome.Cancelled();
        } catch (ExecutionException e) {
            throw rethrowUnexpected(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelRunning(allSlots);
            return new Outcome.Cancelled();
        }
    }

    private void cancelRunning(List<Slot> slots) {
        for (Slot slot : slots) {
            if (slot instanceof Slot.Running running) {
                running.future().cancel(true);
            }
        }
    }

    private Outcome invoke(Task.Ready ready, ReentrantReadWriteLock rwLock) {
        Lock lock = ready.tool().kind().parallelSafe() ? rwLock.readLock() : rwLock.writeLock();
        Message.ToolResult result;
        try (var ignored = cancel.onCancel(Thread.currentThread()::interrupt)) {
            lock.lockInterruptibly();
            try {
                if (cancel.isCancelled()) {
                    return new Outcome.Cancelled();
                }
                String text = ready.tool().execute(ready.call().arguments(), toolContext);
                result = new Message.ToolResult(ready.call().id(), ready.call().name(), text, false);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cancel.isCancelled()) {
                return new Outcome.Cancelled();
            }
            result = Message.ToolResult.error(ready.call(), errorMessage(e));
        }
        emitFinished(result);
        return new Outcome.Finished(result);
    }

    private void emitFinished(Message.ToolResult result) {
        eventLock.lock();
        try {
            onFinished.accept(result);
        } finally {
            eventLock.unlock();
        }
    }

    private static RuntimeException rethrowUnexpected(Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Unexpected checked exception from tool execution", cause);
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
