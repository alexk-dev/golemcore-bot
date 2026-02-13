package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ToolConfirmationPolicy;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BDD: Pipeline gating.
 *
 * <p>
 * Given ToolLoopExecutionSystem is present in the pipeline, when it completes a
 * turn (loop.complete + llm.final.ready), then legacy LlmExecutionSystem and
 * ToolExecutionSystem must not run.
 */
class ToolLoopPipelineGatingBddTest {

    private static final String CHANNEL_TYPE_TELEGRAM = "telegram";
    private static final String CHAT_ID = "123";
    private static final Instant FIXED_TIME = Instant.parse("2026-01-01T12:00:00Z");

    private SessionPort sessionPort;
    private RateLimitPort rateLimiter;
    private BotProperties properties;
    private UserPreferencesService preferencesService;
    private ChannelPort channelPort;
    private Clock clock;

    private LlmPort llmPort;
    private UsageTrackingPort usageTrackingPort;
    private ModelConfigService modelConfigService;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        rateLimiter = mock(RateLimitPort.class);
        when(rateLimiter.tryConsume()).thenReturn(RateLimitResult.allowed(10));

        properties = new BotProperties();
        properties.getAgent().setMaxIterations(3);

        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(anyString(), any(Object[].class))).thenReturn("limit reached");
        when(preferencesService.getMessage("system.error.generic.feedback"))
                .thenReturn("generic feedback");

        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

        llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        usageTrackingPort = mock(UsageTrackingPort.class);
        modelConfigService = mock(ModelConfigService.class);
    }

    private AgentSession createSession() {
        return AgentSession.builder()
                .id("telegram:123")
                .channelType(CHANNEL_TYPE_TELEGRAM)
                .chatId(CHAT_ID)
                .createdAt(FIXED_TIME)
                .updatedAt(FIXED_TIME)
                .build();
    }

    private Message createUserMessage() {
        return Message.builder()
                .id("msg-1")
                .role("user")
                .content("Hello")
                .channelType(CHANNEL_TYPE_TELEGRAM)
                .chatId(CHAT_ID)
                .senderId("user1")
                .timestamp(FIXED_TIME)
                .build();
    }

    @Test
    void shouldSkipLegacyLlmAndToolsWhenToolLoopCompletesTurn() {
        // Given
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);
        doAnswer(invocation -> {
            var context = invocation.getArgument(0, me.golemcore.bot.domain.model.AgentContext.class);
            context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder().content("final").build());
            context.setAttribute(ContextAttributes.LOOP_COMPLETE, true);
            context.setAttribute(DefaultToolLoopSystem.FINAL_ANSWER_READY, true);
            return null;
        }).when(toolLoopSystem).processTurn(any());

        ToolLoopExecutionSystem toolLoopExecutionSystem = new ToolLoopExecutionSystem(toolLoopSystem);

        // legacy systems (spies so we can verify 'process' wasn't called)
        LlmExecutionSystem legacyLlm = spy(new LlmExecutionSystem(llmPort, usageTrackingPort, properties,
                modelConfigService, clock));

        ToolExecutionSystem legacyTools = spy(new ToolExecutionSystem(
                Collections.<ToolComponent>emptyList(),
                mock(ToolConfirmationPolicy.class),
                mock(ConfirmationPort.class),
                properties,
                List.of(channelPort)));

        // stub to prevent AgentLoop feedback guarantee from sending generic feedback
        AgentSystem responseSentStub = new AgentSystem() {
            @Override
            public String getName() {
                return "ResponseSentStub";
            }

            @Override
            public int getOrder() {
                return 60;
            }

            @Override
            public me.golemcore.bot.domain.model.AgentContext process(
                    me.golemcore.bot.domain.model.AgentContext context) {
                assertNotNull(context.getAttribute(ContextAttributes.LLM_RESPONSE), "LLM response must be present");
                context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(sessionPort, rateLimiter, properties,
                List.of(toolLoopExecutionSystem, legacyLlm, legacyTools, responseSentStub),
                List.of(channelPort), preferencesService, llmPort, clock);

        // When
        loop.processMessage(createUserMessage());

        // Then
        verify(toolLoopSystem, times(1)).processTurn(any());

        // gating must prevent legacy systems from running
        verify(legacyLlm, never()).process(any());
        verify(legacyTools, never()).process(any());

        // And legacy LlmPort must not be called (no hidden execution)
        verify(llmPort, never()).chat(any());

        // loop should have completed
        assertTrue(Boolean.TRUE.equals(session.getMessages().stream()
                .anyMatch(m -> "user".equals(m.getRole()))), "User message must be appended to session");
    }

    @Test
    void shouldUseLegacyPlanInterceptFlowWhenPlanModeIsActive() {
        // Given
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        ToolLoopSystem toolLoopSystem = mock(ToolLoopSystem.class);
        ToolLoopExecutionSystem toolLoopExecutionSystem = new ToolLoopExecutionSystem(toolLoopSystem);

        // Pre-system: marks plan mode active in context (ToolLoopExecutionSystem should
        // skip)
        AgentSystem planModeFlagSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "PlanModeFlag";
            }

            @Override
            public int getOrder() {
                return 5;
            }

            @Override
            public me.golemcore.bot.domain.model.AgentContext process(
                    me.golemcore.bot.domain.model.AgentContext context) {
                context.setAttribute(ContextAttributes.PLAN_MODE_ACTIVE, true);
                return context;
            }
        };

        // Legacy LLM: simulate tool calls without calling external provider
        LlmExecutionSystem legacyLlm = spy(new LlmExecutionSystem(llmPort, usageTrackingPort, properties,
                modelConfigService, clock));
        doAnswer(invocation -> {
            var context = invocation.getArgument(0, me.golemcore.bot.domain.model.AgentContext.class);
            Message.ToolCall tc = Message.ToolCall.builder()
                    .id("tc-1")
                    .name("dummy")
                    .arguments(Map.of())
                    .build();
            context.setAttribute(ContextAttributes.LLM_RESPONSE, LlmResponse.builder()
                    .content("planning...")
                    .toolCalls(List.of(tc))
                    .build());
            context.setAttribute("llm.toolCalls", List.of(tc));
            return context;
        }).when(legacyLlm).process(any());

        // PlanInterceptSystem: real system with mocked PlanService
        me.golemcore.bot.domain.service.PlanService planService = mock(
                me.golemcore.bot.domain.service.PlanService.class);
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive()).thenReturn(true);
        when(planService.getActivePlanId()).thenReturn("plan-1");
        when(planService.addStep(anyString(), anyString(), any(), anyString())).thenReturn(null);

        PlanInterceptSystem planInterceptSystem = new PlanInterceptSystem(planService);

        ToolExecutionSystem legacyTools = spy(new ToolExecutionSystem(
                Collections.<ToolComponent>emptyList(),
                mock(ToolConfirmationPolicy.class),
                mock(ConfirmationPort.class),
                properties,
                List.of(channelPort)));

        // stub to prevent feedback guarantee
        AgentSystem responseSentStub = new AgentSystem() {
            @Override
            public String getName() {
                return "ResponseSentStub";
            }

            @Override
            public int getOrder() {
                return 60;
            }

            @Override
            public me.golemcore.bot.domain.model.AgentContext process(
                    me.golemcore.bot.domain.model.AgentContext context) {
                context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
                return context;
            }
        };

        AgentLoop loop = new AgentLoop(sessionPort, rateLimiter, properties,
                List.of(planModeFlagSystem, toolLoopExecutionSystem, legacyLlm, planInterceptSystem, legacyTools,
                        responseSentStub),
                List.of(channelPort), preferencesService, llmPort, clock);

        // When
        loop.processMessage(createUserMessage());

        // Then
        verify(toolLoopSystem, never()).processTurn(any());
        verify(legacyLlm, atLeastOnce()).process(any());
        // PlanIntercept should run (records steps) and clear llm.toolCalls so
        // ToolExecutionSystem won't run
        verify(planService, atLeastOnce()).addStep(eq("plan-1"), anyString(), any(), anyString());
        verify(legacyTools, never()).process(any());
        // LlmPort must not be called (we stubbed legacyLlm.process)
        verify(llmPort, never()).chat(any());
    }
}
