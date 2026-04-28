package me.golemcore.bot.domain.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnRunResult;

final class PendingCompletionRegistry {

    private final Map<Message, List<CompletableFuture<Void>>> completions = Collections
            .synchronizedMap(new IdentityHashMap<>());
    private final Map<Message, List<CompletableFuture<TurnRunResult>>> resultCompletions = Collections
            .synchronizedMap(new IdentityHashMap<>());
    private final Map<Message, List<Runnable>> startCallbacks = Collections.synchronizedMap(new IdentityHashMap<>());

    void registerCompletion(Message message, CompletableFuture<Void> completion) {
        synchronized (completions) {
            completions.computeIfAbsent(message, ignored -> new ArrayList<>()).add(completion);
        }
    }

    void registerResultCompletion(Message message, CompletableFuture<TurnRunResult> completion) {
        synchronized (resultCompletions) {
            resultCompletions.computeIfAbsent(message, ignored -> new ArrayList<>()).add(completion);
        }
    }

    void registerStartCallback(Message message, Runnable onStart) {
        if (message == null || onStart == null) {
            return;
        }
        synchronized (startCallbacks) {
            startCallbacks.computeIfAbsent(message, ignored -> new ArrayList<>()).add(onStart);
        }
    }

    void runStartCallbacks(Message message) {
        for (Runnable callback : removeStartCallbacks(message)) {
            callback.run();
        }
    }

    void transfer(Message source, Message target) {
        List<CompletableFuture<Void>> removedCompletions = removeCompletions(source);
        List<CompletableFuture<TurnRunResult>> removedResultCompletions = removeResultCompletions(source);
        removeStartCallbacks(source);
        if (removedCompletions.isEmpty() && removedResultCompletions.isEmpty()) {
            return;
        }

        if (!removedCompletions.isEmpty()) {
            synchronized (completions) {
                completions.computeIfAbsent(target, ignored -> new ArrayList<>()).addAll(removedCompletions);
            }
        }
        if (!removedResultCompletions.isEmpty()) {
            synchronized (resultCompletions) {
                resultCompletions.computeIfAbsent(target, ignored -> new ArrayList<>())
                        .addAll(removedResultCompletions);
            }
        }
    }

    void complete(Message message) {
        removeStartCallbacks(message);
        for (CompletableFuture<Void> completion : removeCompletions(message)) {
            completion.complete(null);
        }
    }

    void completeResult(Message message, TurnRunResult result) {
        removeStartCallbacks(message);
        for (CompletableFuture<TurnRunResult> completion : removeResultCompletions(message)) {
            completion.complete(result);
        }
    }

    void fail(Message message, Throwable failure) {
        removeStartCallbacks(message);
        for (CompletableFuture<Void> completion : removeCompletions(message)) {
            completion.completeExceptionally(failure);
        }
        for (CompletableFuture<TurnRunResult> completion : removeResultCompletions(message)) {
            completion.completeExceptionally(failure);
        }
    }

    void reject(Message message, String reason) {
        fail(message, new IllegalStateException(reason));
    }

    private List<CompletableFuture<Void>> removeCompletions(Message message) {
        synchronized (completions) {
            List<CompletableFuture<Void>> removed = completions.remove(message);
            if (removed == null || removed.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(removed);
        }
    }

    private List<CompletableFuture<TurnRunResult>> removeResultCompletions(Message message) {
        synchronized (resultCompletions) {
            List<CompletableFuture<TurnRunResult>> removed = resultCompletions.remove(message);
            if (removed == null || removed.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(removed);
        }
    }

    private List<Runnable> removeStartCallbacks(Message message) {
        synchronized (startCallbacks) {
            List<Runnable> callbacks = startCallbacks.remove(message);
            if (callbacks == null || callbacks.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(callbacks);
        }
    }
}
