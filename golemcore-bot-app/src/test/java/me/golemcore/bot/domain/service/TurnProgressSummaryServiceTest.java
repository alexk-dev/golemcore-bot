package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.ToolExecutionTrace;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnProgressSummaryServiceTest {

    private LlmPort llmPort;
    private RuntimeConfigService runtimeConfigService;
    private TurnProgressSummaryService service;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getBalancedModel()).thenReturn("openai/gpt-5.1");
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("none");
        when(runtimeConfigService.getTurnProgressSummaryTimeoutMs()).thenReturn(5000);
        service = new TurnProgressSummaryService(
                llmPort,
                runtimeConfigService,
                Clock.fixed(Instant.parse("2026-03-17T18:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldUseLlmSummaryWhenAvailable() {
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                .content("Checked the repo and grouped the latest shell runs.")
                .build()));

        String summary = service.summarize(buildContext(), traces());

        assertEquals("Checked the repo and grouped the latest shell runs.", summary);
    }

    @Test
    void shouldFallbackToDeterministicSummaryWhenLlmUnavailable() {
        when(llmPort.isAvailable()).thenReturn(false);

        String summary = service.summarize(buildContext(), traces());

        assertTrue(summary.contains("shell commands"));
        assertTrue(summary.contains("Latest step"));
    }

    private AgentContext buildContext() {
        return AgentContext.builder()
                .session(AgentSession.builder().id("session-1").build())
                .messages(new ArrayList<>())
                .build();
    }

    private List<ToolExecutionTrace> traces() {
        return List.of(
                new ToolExecutionTrace("shell", "shell", "ran `rg progress`", true, 50L, Map.of()),
                new ToolExecutionTrace("shell", "shell", "ran `mvn test`", true, 120L, Map.of()));
    }
}
