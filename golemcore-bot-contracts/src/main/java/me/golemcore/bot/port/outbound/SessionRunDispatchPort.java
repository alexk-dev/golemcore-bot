package me.golemcore.bot.port.outbound;

import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnRunResult;

/**
 * Session run queue operations exposed to capability modules.
 */
public interface SessionRunDispatchPort {

    CompletableFuture<Void> submit(Message inbound, Runnable onStart);

    CompletableFuture<TurnRunResult> submitForResult(Message inbound);

    void requestStop(String channelType, String chatId, String expectedRunId, String expectedCommandId);
}
