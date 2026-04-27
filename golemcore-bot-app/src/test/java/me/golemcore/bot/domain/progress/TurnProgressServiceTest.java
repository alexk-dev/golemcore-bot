package me.golemcore.bot.domain.progress;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.tracing.ToolExecutionTraceExtractor;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.ProgressUpdateType;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.port.channel.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static me.golemcore.bot.support.ChannelRuntimeTestSupport.runtime;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnProgressServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private ChannelPort channelPort;
    private TurnProgressSummaryService summaryService;
    private TurnProgressService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        channelPort = mock(ChannelPort.class);
        summaryService = mock(TurnProgressSummaryService.class);

        when(runtimeConfigService.isTurnProgressUpdatesEnabled()).thenReturn(true);
        when(runtimeConfigService.isTurnProgressIntentEnabled()).thenReturn(true);
        when(runtimeConfigService.getTurnProgressBatchSize()).thenReturn(2);
        when(runtimeConfigService.getTurnProgressMaxSilence()).thenReturn(Duration.ofSeconds(10));
        when(channelPort.getChannelType()).thenReturn("web");
        when(channelPort.sendProgressUpdate(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(summaryService.summarize(any(), any())).thenReturn("Grouped the latest checks into one update.");

        service = new TurnProgressService(
                runtimeConfigService,
                runtime(channelPort),
                new ToolExecutionTraceExtractor(),
                summaryService,
                Clock.fixed(Instant.parse("2026-03-17T18:05:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldPublishIntentOnlyOnce() {
        AgentContext context = buildContext();
        LlmResponse response = LlmResponse.builder()
                .toolCalls(List.of(toolCall("shell"), toolCall("filesystem")))
                .build();

        service.maybePublishIntent(context, response);
        service.maybePublishIntent(context, response);

        verify(channelPort, times(1)).sendProgressUpdate(eq("chat-1"), any(ProgressUpdate.class));
    }

    @Test
    void shouldFlushWhenBatchSizeIsReachedAndClearAtTheEnd() {
        AgentContext context = buildContext();

        service.recordToolExecution(context, toolCall("shell"),
                new ToolExecutionOutcome("tc-1", "shell", ToolResult.success("ok"), "ok", false, null),
                20L);
        service.recordToolExecution(context, toolCall("shell"),
                new ToolExecutionOutcome("tc-2", "shell", ToolResult.success("ok"), "ok", false, null),
                25L);
        service.clearProgress(context);

        ArgumentCaptor<ProgressUpdate> captor = ArgumentCaptor.forClass(ProgressUpdate.class);
        verify(channelPort, times(2)).sendProgressUpdate(eq("chat-1"), captor.capture());
        List<ProgressUpdate> updates = captor.getAllValues();
        assertEquals(ProgressUpdateType.SUMMARY, updates.get(0).type());
        assertEquals("Grouped the latest checks into one update.", updates.get(0).text());
        assertEquals(ProgressUpdateType.CLEAR, updates.get(1).type());
        assertTrue(((List<?>) updates.get(0).metadata().get("families")).contains("shell"));
    }

    @Test
    void shouldFlushOnFamilyChangeBeforeStartingNewBatch() {
        AgentContext context = buildContext();

        service.recordToolExecution(context, toolCall("shell"),
                new ToolExecutionOutcome("tc-1", "shell", ToolResult.success("ok"), "ok", false, null),
                20L);
        service.recordToolExecution(context, toolCall("brave_search"),
                new ToolExecutionOutcome("tc-2", "brave_search", ToolResult.success("ok"), "ok", false, null),
                25L);

        ArgumentCaptor<ProgressUpdate> captor = ArgumentCaptor.forClass(ProgressUpdate.class);
        verify(channelPort).sendProgressUpdate(eq("chat-1"), captor.capture());
        assertEquals(ProgressUpdateType.SUMMARY, captor.getValue().type());
    }

    @Test
    void shouldPropagateHiveMetadataIntoProgressUpdates() {
        ChannelPort hiveChannel = mock(ChannelPort.class);
        when(hiveChannel.getChannelType()).thenReturn("hive");
        when(hiveChannel.sendProgressUpdate(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        TurnProgressService hiveService = new TurnProgressService(
                runtimeConfigService,
                runtime(hiveChannel),
                new ToolExecutionTraceExtractor(),
                summaryService,
                Clock.fixed(Instant.parse("2026-03-17T18:05:00Z"), ZoneOffset.UTC));
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");
        context.setAttribute(ContextAttributes.HIVE_COMMAND_ID, "cmd-1");
        context.setAttribute(ContextAttributes.HIVE_RUN_ID, "run-1");

        hiveService.clearProgress(context);

        ArgumentCaptor<ProgressUpdate> captor = ArgumentCaptor.forClass(ProgressUpdate.class);
        verify(hiveChannel).sendProgressUpdate(eq("thread-1"), captor.capture());
        ProgressUpdate update = captor.getValue();
        assertEquals("thread-1", update.metadata().get(ContextAttributes.HIVE_THREAD_ID));
        assertEquals("card-1", update.metadata().get(ContextAttributes.HIVE_CARD_ID));
        assertEquals("cmd-1", update.metadata().get(ContextAttributes.HIVE_COMMAND_ID));
        assertEquals("run-1", update.metadata().get(ContextAttributes.HIVE_RUN_ID));
    }

    private AgentContext buildContext() {
        return AgentContext.builder()
                .session(AgentSession.builder()
                        .id("session-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .build())
                .messages(new ArrayList<>(List.of(Message.builder()
                        .role("user")
                        .content("Check the current repo state and summarize the latest shell work.")
                        .build())))
                .build();
    }

    private Message.ToolCall toolCall(String toolName) {
        return Message.ToolCall.builder()
                .id("id-" + toolName)
                .name(toolName)
                .arguments(Map.of(
                        "command", "rg progress",
                        "query", "repo status",
                        "operation", "read_file",
                        "path", "README.md"))
                .build();
    }
}
