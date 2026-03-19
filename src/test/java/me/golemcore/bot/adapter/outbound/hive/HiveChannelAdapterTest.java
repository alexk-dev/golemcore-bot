package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.ProgressUpdateType;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveChannelAdapterTest {

    private HiveEventBatchPublisher publisher;
    private HiveChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        publisher = mock(HiveEventBatchPublisher.class);
        adapter = new HiveChannelAdapter(publisher);
    }

    @Test
    void shouldExposeHiveLifecycleAndAuthorizationState() {
        assertTrue(adapter.isRunning());
        assertTrue(adapter.isAuthorized("any-user"));
        assertTrue("hive".equals(adapter.getChannelType()));

        adapter.stop();
        assertFalse(adapter.isRunning());

        adapter.start();
        assertTrue(adapter.isRunning());
        assertDoesNotThrow(() -> adapter.onMessage(message -> {
        }));
        assertDoesNotThrow(() -> adapter.sendVoice("thread-1", new byte[] { 1, 2 }).join());
    }

    @Test
    void shouldPublishThreadMessages() {
        adapter.sendMessage("thread-1", "hello").join();
        verify(publisher).publishThreadMessage("thread-1", "hello", Map.of());

        Map<String, Object> hints = Map.of("cardId", "card-1");
        adapter.sendMessage("thread-1", "details", hints).join();
        verify(publisher).publishThreadMessage("thread-1", "details", hints);

        Message message = Message.builder()
                .chatId("thread-2")
                .content("from message")
                .metadata(Map.of("commandId", "cmd-1"))
                .build();
        adapter.sendMessage(message).join();
        verify(publisher).publishThreadMessage("thread-2", "from message", message.getMetadata());

        assertDoesNotThrow(() -> adapter.sendMessage((Message) null).join());
    }

    @Test
    void shouldPublishRuntimeEventsAndProgressUpdates() {
        RuntimeEvent runtimeEvent = RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .timestamp(Instant.parse("2026-03-19T00:00:00Z"))
                .chatId("thread-1")
                .payload(Map.of("runId", "run-1"))
                .build();
        adapter.sendRuntimeEvent("thread-ignored", runtimeEvent).join();
        verify(publisher).publishRuntimeEvents(java.util.List.of(runtimeEvent), runtimeEvent.payload());

        adapter.sendRuntimeEvent("thread-ignored", null).join();
        verify(publisher).publishRuntimeEvents(java.util.List.of(), Map.of());

        ProgressUpdate update = new ProgressUpdate(
                ProgressUpdateType.SUMMARY,
                "working",
                Map.of("threadId", "thread-1"));
        adapter.sendProgressUpdate("thread-1", update).join();
        verify(publisher).publishProgressUpdate("thread-1", update);
    }

    @Test
    void shouldReturnFailedFutureWhenPublishingThreadMessageFails() {
        doThrow(new IllegalStateException("boom"))
                .when(publisher)
                .publishThreadMessage(eq("thread-1"), eq("hello"), any());

        CompletionException exception = assertThrows(CompletionException.class,
                () -> adapter.sendMessage("thread-1", "hello", Map.of()).join());
        assertTrue(exception.getCause() instanceof IllegalStateException);

        doThrow(new IllegalStateException("message-boom"))
                .when(publisher)
                .publishThreadMessage(eq("thread-2"), eq("from message"), any());

        Message message = Message.builder()
                .chatId("thread-2")
                .content("from message")
                .metadata(Map.of())
                .build();
        CompletionException messageException = assertThrows(CompletionException.class,
                () -> adapter.sendMessage(message).join());
        assertTrue(messageException.getCause() instanceof IllegalStateException);
    }

    @Test
    void shouldReturnFailedFutureWhenPublishingRuntimeOrProgressFails() {
        RuntimeEvent runtimeEvent = RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_FAILED)
                .timestamp(Instant.parse("2026-03-19T00:00:00Z"))
                .payload(Map.of())
                .build();
        doThrow(new IllegalStateException("runtime-boom"))
                .when(publisher)
                .publishRuntimeEvents(java.util.List.of(runtimeEvent), Map.of());

        CompletionException runtimeException = assertThrows(CompletionException.class,
                () -> adapter.sendRuntimeEvent("thread-1", runtimeEvent).join());
        assertTrue(runtimeException.getCause() instanceof IllegalStateException);

        ProgressUpdate update = new ProgressUpdate(ProgressUpdateType.SUMMARY, "working", Map.of());
        doThrow(new IllegalStateException("progress-boom"))
                .when(publisher)
                .publishProgressUpdate("thread-1", update);

        CompletionException progressException = assertThrows(CompletionException.class,
                () -> adapter.sendProgressUpdate("thread-1", update).join());
        assertTrue(progressException.getCause() instanceof IllegalStateException);
    }
}
