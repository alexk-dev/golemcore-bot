package me.golemcore.bot.domain.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnRunResult;
import org.junit.jupiter.api.Test;

class PendingCompletionRegistryTest {

    @Test
    void shouldCompleteVoidAndResultCompletions() {
        PendingCompletionRegistry registry = new PendingCompletionRegistry();
        Message message = message("first");
        CompletableFuture<Void> voidCompletion = new CompletableFuture<>();
        CompletableFuture<TurnRunResult> resultCompletion = new CompletableFuture<>();
        TurnRunResult result = TurnRunResult.skipped("session-1", "done");

        registry.registerCompletion(message, voidCompletion);
        registry.registerResultCompletion(message, resultCompletion);
        registry.complete(message);
        registry.completeResult(message, result);

        assertEquals(null, voidCompletion.join());
        assertSame(result, resultCompletion.join());
    }

    @Test
    void shouldTransferCompletionsByMessageIdentity() {
        PendingCompletionRegistry registry = new PendingCompletionRegistry();
        Message source = message("source");
        Message target = message("target");
        CompletableFuture<TurnRunResult> resultCompletion = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();

        registry.registerResultCompletion(source, resultCompletion);
        registry.registerStartCallback(source, () -> started.set(true));
        registry.transfer(source, target);
        registry.runStartCallbacks(source);
        registry.completeResult(target, TurnRunResult.skipped("session-1", "transferred"));

        assertEquals(false, started.get());
        assertEquals("transferred", resultCompletion.join().persistence().errorMessage());
    }

    @Test
    void shouldFailPendingCompletions() {
        PendingCompletionRegistry registry = new PendingCompletionRegistry();
        Message message = message("failed");
        CompletableFuture<Void> completion = new CompletableFuture<>();

        registry.registerCompletion(message, completion);
        registry.reject(message, "rejected");

        CompletionException exception = assertThrows(CompletionException.class, completion::join);
        assertSame(IllegalStateException.class, exception.getCause().getClass());
    }

    private static Message message(String content) {
        return Message.builder().role("user").content(content).channelType("telegram").chatId("1").build();
    }
}
