package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.ProgressUpdateType;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.service.HiveSessionStateStore;

class HiveEventBatchPublisherTest {

    private HiveSessionStateStore hiveSessionStateStore;
    private HiveApiClient hiveApiClient;
    private HiveEventBatchPublisher publisher;

    @BeforeEach
    void setUp() {
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveApiClient = mock(HiveApiClient.class);
        publisher = new HiveEventBatchPublisher(
                hiveSessionStateStore,
                hiveApiClient,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .accessToken("access")
                .build()));
    }

    @Test
    void shouldPublishThreadMessageAndUsageEvent() {
        publisher.publishThreadMessage("thread-1", "Done", Map.of(
                ContextAttributes.HIVE_THREAD_ID, "thread-1",
                ContextAttributes.HIVE_CARD_ID, "card-1",
                ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                ContextAttributes.HIVE_RUN_ID, "run-1",
                "inputTokens", 120L,
                "outputTokens", 45L));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(2, events.size());
        assertEquals("THREAD_MESSAGE", events.get(0).runtimeEventType());
        assertEquals("thread-1", events.get(0).threadId());
        assertEquals("cmd-1", events.get(0).commandId());
        assertEquals("Done", events.get(0).details());
        assertEquals("USAGE_REPORTED", events.get(1).runtimeEventType());
        assertEquals(120L, events.get(1).inputTokens());
        assertEquals(45L, events.get(1).outputTokens());
    }

    @Test
    void shouldMapRuntimeEventsToHiveRuntimeTypes() {
        List<RuntimeEvent> runtimeEvents = List.of(
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_STARTED)
                        .timestamp(Instant.parse("2026-03-18T00:00:01Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of())
                        .build(),
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TOOL_STARTED)
                        .timestamp(Instant.parse("2026-03-18T00:00:02Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("tool", "shell"))
                        .build(),
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FINISHED)
                        .timestamp(Instant.parse("2026-03-18T00:00:03Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("reason", "completed"))
                        .build());

        publisher.publishRuntimeEvents(runtimeEvents, Map.of(
                ContextAttributes.HIVE_THREAD_ID, "thread-1",
                ContextAttributes.HIVE_CARD_ID, "card-1",
                ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                ContextAttributes.HIVE_RUN_ID, "run-1"));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(List.of("RUN_STARTED", "RUN_PROGRESS", "RUN_COMPLETED"),
                events.stream()
                        .filter(event -> "runtime_event".equals(event.eventType()))
                        .map(HiveEventPayload::runtimeEventType)
                        .toList());
        assertEquals(List.of("WORK_STARTED"),
                events.stream()
                        .filter(event -> "card_lifecycle_signal".equals(event.eventType()))
                        .map(HiveEventPayload::signalType)
                        .toList());
        assertEquals("card-1", events.get(0).cardId());
        assertEquals("run-1", events.get(0).runId());
    }

    @Test
    void shouldPublishProgressUpdateWithoutHiveKeysInDetails() {
        publisher.publishProgressUpdate("thread-1", new ProgressUpdate(
                ProgressUpdateType.SUMMARY,
                "Progress update",
                Map.of(
                        ContextAttributes.HIVE_THREAD_ID, "thread-1",
                        ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                        "toolCount", 2)));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        HiveEventPayload event = eventsCaptor.getValue().get(0);
        assertEquals("RUN_PROGRESS", event.runtimeEventType());
        assertEquals("Progress update", event.summary());
        org.junit.jupiter.api.Assertions.assertFalse(event.details().contains(ContextAttributes.HIVE_THREAD_ID));
        org.junit.jupiter.api.Assertions.assertTrue(event.details().contains("toolCount"));
    }

    @Test
    void shouldSkipPublishWithoutActiveHiveSession() {
        when(hiveSessionStateStore.load()).thenReturn(Optional.empty());

        publisher.publishCommandAcknowledged(HiveControlCommandEnvelope.builder()
                .commandId("cmd-1")
                .threadId("thread-1")
                .body("Do work")
                .build());

        verify(hiveApiClient, never()).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                anyList());
    }

    @Test
    void shouldPublishExplicitLifecycleSignal() {
        publisher.publishLifecycleSignal(
                new HiveLifecycleSignalRequest(
                        "BLOCKER_RAISED",
                        "Missing credentials",
                        "Need staging API key",
                        "missing_credentials",
                        List.of(new HiveEvidenceRef("run_log", "run-1/log-2")),
                        Instant.parse("2026-03-18T00:02:00Z")),
                Map.of(
                        ContextAttributes.HIVE_THREAD_ID, "thread-1",
                        ContextAttributes.HIVE_CARD_ID, "card-1",
                        ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                        ContextAttributes.HIVE_RUN_ID, "run-1"));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        HiveEventPayload event = eventsCaptor.getValue().get(0);
        assertEquals("card_lifecycle_signal", event.eventType());
        assertEquals("BLOCKER_RAISED", event.signalType());
        assertEquals("Missing credentials", event.summary());
        assertEquals("missing_credentials", event.blockerCode());
        assertEquals(1, event.evidenceRefs().size());
        assertEquals("run_log", event.evidenceRefs().get(0).kind());
    }

    @Test
    void shouldMapFailedAndCancelledRuntimeEventsToLifecycleSignals() {
        List<RuntimeEvent> runtimeEvents = List.of(
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FAILED)
                        .timestamp(Instant.parse("2026-03-18T00:00:01Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("reason", "tool_error", "code", "shell_failed"))
                        .build(),
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FINISHED)
                        .timestamp(Instant.parse("2026-03-18T00:00:02Z"))
                        .sessionId("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .payload(Map.of("reason", "user_interrupt"))
                        .build());

        publisher.publishRuntimeEvents(runtimeEvents, Map.of(
                ContextAttributes.HIVE_THREAD_ID, "thread-1",
                ContextAttributes.HIVE_CARD_ID, "card-1",
                ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                ContextAttributes.HIVE_RUN_ID, "run-1"));

        ArgumentCaptor<List<HiveEventPayload>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(hiveApiClient).publishEventsBatch(eq("https://hive.example.com"), eq("golem-1"), eq("access"),
                eventsCaptor.capture());
        List<HiveEventPayload> events = eventsCaptor.getValue();
        assertEquals(List.of("RUN_FAILED", "RUN_CANCELLED"),
                events.stream()
                        .filter(event -> "runtime_event".equals(event.eventType()))
                        .map(HiveEventPayload::runtimeEventType)
                        .toList());
        assertEquals(List.of("WORK_FAILED", "WORK_CANCELLED"),
                events.stream()
                        .filter(event -> "card_lifecycle_signal".equals(event.eventType()))
                        .map(HiveEventPayload::signalType)
                        .toList());
    }
}
