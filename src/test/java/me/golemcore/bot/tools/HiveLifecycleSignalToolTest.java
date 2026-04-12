package me.golemcore.bot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveLifecycleSignalToolTest {

    @BeforeEach
    void setUp() {
        AgentContextHolder.set(AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("hive")
                        .chatId("thread-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build());
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    private RuntimeConfigService enabledRuntimeConfigService() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        org.mockito.Mockito.when(runtimeConfigService.isHiveSdlcLifecycleSignalEnabled()).thenReturn(true);
        return runtimeConfigService;
    }

    @Test
    void shouldPublishLifecycleSignalForHiveSession() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveLifecycleSignalTool tool = new HiveLifecycleSignalTool(
                publisher,
                enabledRuntimeConfigService(),
                Clock.fixed(Instant.parse("2026-03-18T12:00:00Z"), ZoneOffset.UTC));

        AgentContext context = AgentContextHolder.get();
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");
        context.setAttribute(ContextAttributes.HIVE_COMMAND_ID, "cmd-1");
        context.setAttribute(ContextAttributes.HIVE_RUN_ID, "run-1");

        ToolResult result = tool.execute(Map.of(
                "signal_type", "work_completed",
                "summary", "Implementation complete",
                "details", "Tests passed",
                "evidence_refs", List.of(Map.of("kind", "artifact", "ref", "artifact-1")))).join();

        assertEquals(true, result.isSuccess());
        verify(publisher).publishLifecycleSignal(
                argThat(request -> "WORK_COMPLETED".equals(request.signalType())
                        && "Implementation complete".equals(request.summary())
                        && "Tests passed".equals(request.details())
                        && request.evidenceRefs().size() == 1
                        && "artifact".equals(request.evidenceRefs().get(0).kind())
                        && "artifact-1".equals(request.evidenceRefs().get(0).ref())),
                argThat(metadata -> "thread-1".equals(metadata.get(ContextAttributes.HIVE_THREAD_ID))
                        && "card-1".equals(metadata.get(ContextAttributes.HIVE_CARD_ID))
                        && "cmd-1".equals(metadata.get(ContextAttributes.HIVE_COMMAND_ID))
                        && "run-1".equals(metadata.get(ContextAttributes.HIVE_RUN_ID))));
    }

    @Test
    void shouldDenyLifecycleSignalOutsideHiveSession() {
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveLifecycleSignalTool tool = new HiveLifecycleSignalTool(publisher, enabledRuntimeConfigService(),
                Clock.systemUTC());

        AgentContextHolder.set(AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build());

        ToolResult result = tool.execute(Map.of(
                "signal_type", "WORK_COMPLETED",
                "summary", "Done")).join();

        assertFalse(result.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, result.getFailureKind());
    }
}
