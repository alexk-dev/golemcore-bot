package me.golemcore.bot.adapter.outbound.hive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.domain.selfevolving.tactic.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.system.HiveRuntimeEventDispatchSystem;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import org.junit.jupiter.api.Test;

class HiveRuntimeEventDispatchSystemTest {

    @Test
    void shouldDispatchHiveRuntimeEventsWithTurnMetadata() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(publisher, null, null, null);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of(RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .timestamp(Instant.parse("2026-03-18T00:00:00Z"))
                .sessionId("hive:thread-1")
                .channelType("hive")
                .chatId("thread-1")
                .payload(Map.of())
                .build()));
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");
        context.setAttribute(ContextAttributes.HIVE_COMMAND_ID, "cmd-1");
        context.setAttribute(ContextAttributes.HIVE_RUN_ID, "run-1");

        system.process(context);

        verify(publisher).publishRuntimeEvents(anyList(),
                argThat(metadata -> "thread-1".equals(metadata.get(ContextAttributes.HIVE_THREAD_ID))
                        && "card-1".equals(metadata.get(ContextAttributes.HIVE_CARD_ID))
                        && "cmd-1".equals(metadata.get(ContextAttributes.HIVE_COMMAND_ID))
                        && "run-1".equals(metadata.get(ContextAttributes.HIVE_RUN_ID))));
    }

    @Test
    void shouldSkipNonHiveSessions() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(publisher, null, null, null);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();

        system.process(context);

        verifyNoInteractions(publisher);
    }

    @Test
    void shouldPublishRuntimeTacticSearchProjectionFromContextAttributes() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveSessionStateStore sessionStateStore = mock(HiveSessionStateStore.class);
        LocalEmbeddingBootstrapService localEmbeddingBootstrapService = mock(LocalEmbeddingBootstrapService.class);
        when(sessionStateStore.load())
                .thenReturn(java.util.Optional.of(HiveSessionState.builder()
                        .serverUrl("https://hive.example")
                        .golemId("golem-1")
                        .accessToken("token")
                        .build()));
        when(localEmbeddingBootstrapService.probeStatus()).thenReturn(TacticSearchStatus.builder()
                .mode("bm25")
                .reason("Managed Ollama exited with code 137")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .degraded(true)
                .runtimeState("degraded_restart_backoff")
                .owned(true)
                .restartAttempts(2)
                .nextRetryTime("2026-04-02T00:31:00Z")
                .runtimeHealthy(false)
                .modelAvailable(false)
                .build());
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(
                publisher,
                null,
                sessionStateStore,
                localEmbeddingBootstrapService);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, TacticSearchQuery.builder()
                .rawQuery("recover failed shell command")
                .queryViews(List.of("recover", "shell"))
                .build());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS, List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .artifactStreamId("stream-planner")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner tactic")
                .promotionState("active")
                .rolloutStage("active")
                .score(1.12d)
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .eligible(true)
                        .finalScore(1.12d)
                        .build())
                .build()));

        system.process(context);

        verify(publisher).publishSelfEvolvingTacticSearchProjection(
                argThat(query -> "recover failed shell command".equals(query)),
                argThat(status -> status != null
                        && "degraded_restart_backoff".equals(status.getRuntimeState())
                        && Boolean.TRUE.equals(status.getOwned())
                        && Integer.valueOf(2).equals(status.getRestartAttempts())),
                anyList());
    }

    @Test
    void shouldSkipRuntimeTacticSearchProjectionWhenHiveSessionIsUnavailable() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveSessionStateStore sessionStateStore = mock(HiveSessionStateStore.class);
        when(sessionStateStore.load()).thenReturn(java.util.Optional.empty());
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(
                publisher,
                null,
                sessionStateStore,
                null);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, TacticSearchQuery.builder()
                .rawQuery("recover failed shell command")
                .queryViews(List.of("recover", "shell"))
                .build());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_RESULTS, List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .artifactStreamId("stream-planner")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner tactic")
                .promotionState("active")
                .rolloutStage("active")
                .score(1.12d)
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .eligible(true)
                        .finalScore(1.12d)
                        .build())
                .build()));

        system.process(context);

        verifyNoInteractions(publisher);
    }

    @Test
    void shouldSkipRuntimeTacticSearchProjectionWhenHiveSessionIsIncomplete() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveSessionStateStore sessionStateStore = mock(HiveSessionStateStore.class);
        when(sessionStateStore.load())
                .thenReturn(java.util.Optional.of(HiveSessionState.builder()
                        .serverUrl("https://hive.example")
                        .golemId("golem-1")
                        .accessToken(" ")
                        .build()));
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(
                publisher,
                null,
                sessionStateStore,
                null);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, TacticSearchQuery.builder()
                .rawQuery("recover failed shell command")
                .queryViews(List.of("recover", "shell"))
                .build());

        system.process(context);

        verifyNoInteractions(publisher);
    }

    @Test
    void shouldPublishRuntimeTacticSearchProjectionUsingQueryViewsWhenRawQueryIsBlank() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveSessionStateStore sessionStateStore = mock(HiveSessionStateStore.class);
        when(sessionStateStore.load())
                .thenReturn(java.util.Optional.of(HiveSessionState.builder()
                        .serverUrl("https://hive.example")
                        .golemId("golem-1")
                        .accessToken("token")
                        .build()));
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(
                publisher,
                null,
                sessionStateStore,
                null);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, TacticSearchQuery.builder()
                .rawQuery("  ")
                .queryViews(List.of("recover", "shell"))
                .build());

        system.process(context);

        verify(publisher).publishSelfEvolvingTacticSearchProjection(
                argThat(query -> "recover shell".equals(query)),
                any(TacticSearchStatus.class),
                anyList());
    }

    @Test
    void shouldSwallowRuntimeTacticSearchProjectionPublishFailures() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveSessionStateStore sessionStateStore = mock(HiveSessionStateStore.class);
        when(sessionStateStore.load())
                .thenReturn(java.util.Optional.of(HiveSessionState.builder()
                        .serverUrl("https://hive.example")
                        .golemId("golem-1")
                        .accessToken("token")
                        .build()));
        doThrow(new IllegalStateException("publish failed"))
                .when(publisher)
                .publishSelfEvolvingTacticSearchProjection(any(String.class), any(TacticSearchStatus.class), anyList());
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(
                publisher,
                null,
                sessionStateStore,
                null);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_QUERY, TacticSearchQuery.builder()
                .rawQuery("recover failed shell command")
                .queryViews(List.of("recover", "shell"))
                .build());

        assertDoesNotThrow(() -> system.process(context));
    }
}
