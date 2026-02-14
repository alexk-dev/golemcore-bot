package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.port.outbound.RateLimitPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private static final String CHANNEL_TYPE_TELEGRAM = "telegram";
    private static final String CHAT_ID = "123";
    private static final Instant FIXED_TIME = Instant.parse("2026-01-01T12:00:00Z");
    private static final String GENERIC_FEEDBACK = "Something went wrong while processing your request. Please try again.";
    private static final String ROLE_USER = "user";
    private static final String KEY_ERROR_FEEDBACK = "system.error.feedback";

    private SessionPort sessionPort;
    private RateLimitPort rateLimiter;
    private BotProperties properties;
    private UserPreferencesService preferencesService;
    private ChannelPort channelPort;
    private LlmPort llmPort;
    private Clock clock;

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        rateLimiter = mock(RateLimitPort.class);
        properties = new BotProperties();
        properties.getAgent().setMaxIterations(5);
        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(anyString(), any(Object[].class))).thenReturn("limit reached");
        when(preferencesService.getMessage("system.error.generic.feedback")).thenReturn(GENERIC_FEEDBACK);

        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE_TELEGRAM);
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        llmPort = mock(LlmPort.class);
        when(llmPort.isAvailable()).thenReturn(false);

        clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

        when(rateLimiter.tryConsume()).thenReturn(RateLimitResult.allowed(10));
    }

    private AgentLoop createLoop(List<AgentSystem> systems) {
        return new AgentLoop(sessionPort, rateLimiter, properties, systems,
                List.of(channelPort), preferencesService, llmPort, clock);
    }

    private Message createUserMessage() {
        return Message.builder()
                .id("msg-1")
                .role(ROLE_USER)
                .content("Hello")
                .channelType(CHANNEL_TYPE_TELEGRAM)
                .chatId(CHAT_ID)
                .senderId("user1")
                .timestamp(FIXED_TIME)
                .build();
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

    // ===== System ordering =====

    @Test
    void systemsCalledInOrder() {
        List<String> callOrder = new ArrayList<>();

        AgentSystem system10 = createOrderedSystem("A", 10, callOrder);
        AgentSystem system20 = createOrderedSystem("B", 20, callOrder);
        AgentSystem system30 = createOrderedSystem("C", 30, callOrder);

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(system30, system10, system20)); // intentionally unordered
        loop.processMessage(createUserMessage());

        assertEquals(List.of("A", "B", "C"), callOrder);
        verify(sessionPort).save(session);
    }
    // ===== Loop stops when no tool calls =====

    @Test
    void loopStopsWithNoToolCalls() {
        List<Integer> iterations = new ArrayList<>();

        AgentSystem system = new AgentSystem() {
            @Override
            public String getName() {
                return "NoTool";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                iterations.add(context.getCurrentIteration());
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(system));
        loop.processMessage(createUserMessage());

        assertEquals(1, iterations.size(), "Should run exactly 1 iteration when no tools");
    }

    // ===== Max iterations limit =====

    @Test
    void maxIterationsLimitEnforced() {
        properties.getAgent().setMaxIterations(3);
        List<Integer> iterations = new ArrayList<>();

        // System that always requests another iteration via skill pipeline transition
        AgentSystem infiniteLoop = new AgentSystem() {
            @Override
            public String getName() {
                return "InfiniteLoop";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                iterations.add(context.getCurrentIteration());
                context.setSkillTransitionRequest(
                        me.golemcore.bot.domain.model.SkillTransitionRequest.explicit("skill-next"));
                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, Boolean.FALSE);
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(infiniteLoop));
        loop.processMessage(createUserMessage());

        assertEquals(3, iterations.size(), "Should stop after max iterations");
        // Verify iteration limit notification sent to channel
        verify(channelPort).sendMessage(CHAT_ID, "limit reached");
    }

    // ===== Rate limiting =====

    @Test
    void rateLimitBlocksMessage() {
        when(rateLimiter.tryConsume())
                .thenReturn(RateLimitResult.denied(1000, "Rate limit exceeded"));

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        List<Integer> iterations = new ArrayList<>();
        AgentSystem system = createTrackingSystem(iterations);

        AgentLoop loop = createLoop(List.of(system));
        loop.processMessage(createUserMessage());

        assertTrue(iterations.isEmpty(), "No systems should run when rate-limited");
        verify(sessionPort, never()).save(any());
    }

    // ===== Session created and saved =====

    @Test
    void sessionCreatedAndSaved() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentSystem system = new AgentSystem() {
            @Override
            public String getName() {
                return "PassThrough";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(system));
        loop.processMessage(createUserMessage());

        verify(sessionPort).getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID);
        verify(sessionPort).save(session);
        // Message should be added to session
        assertFalse(session.getMessages().isEmpty());
    }

    // ===== Disabled systems skipped =====

    @Test
    void disabledSystemsSkipped() {
        List<String> callOrder = new ArrayList<>();

        AgentSystem enabled = createOrderedSystem("Enabled", 10, callOrder);
        AgentSystem disabled = new AgentSystem() {
            @Override
            public String getName() {
                return "Disabled";
            }

            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public AgentContext process(AgentContext context) {
                callOrder.add("Disabled");
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(enabled, disabled));
        loop.processMessage(createUserMessage());

        assertEquals(List.of("Enabled"), callOrder);
    }

    // ===== shouldProcess=false skips system =====

    @Test
    void shouldProcessFalseSkipsSystem() {
        List<String> callOrder = new ArrayList<>();

        AgentSystem always = createOrderedSystem("Always", 10, callOrder);
        AgentSystem conditional = new AgentSystem() {
            @Override
            public String getName() {
                return "Conditional";
            }

            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public boolean shouldProcess(AgentContext context) {
                return false;
            }

            @Override
            public AgentContext process(AgentContext context) {
                callOrder.add("Conditional");
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(always, conditional));
        loop.processMessage(createUserMessage());

        assertEquals(List.of("Always"), callOrder);
    }

    // ===== Auto mode skips rate limiter =====

    @Test
    void autoModeSkipsRateLimiter() {
        when(rateLimiter.tryConsume())
                .thenReturn(RateLimitResult.denied(1000, "Rate limit exceeded"));

        Message autoMessage = Message.builder()
                .id("auto-1")
                .role(ROLE_USER)
                .content("Auto check")
                .channelType(CHANNEL_TYPE_TELEGRAM)
                .chatId(CHAT_ID)
                .senderId("bot")
                .timestamp(FIXED_TIME)
                .metadata(Map.of("auto.mode", true))
                .build();

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        List<Integer> iterations = new ArrayList<>();
        AgentSystem system = createTrackingSystem(iterations);

        AgentLoop loop = createLoop(List.of(system));
        loop.processMessage(autoMessage);

        assertFalse(iterations.isEmpty(), "Auto mode should bypass rate limiter");
        verify(sessionPort).save(session);
    }

    // ===== System exception doesn't crash loop =====

    @Test
    void systemExceptionDoesNotCrashLoop() {
        List<String> callOrder = new ArrayList<>();

        AgentSystem failing = new AgentSystem() {
            @Override
            public String getName() {
                return "Failing";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                callOrder.add("Failing");
                throw new RuntimeException("System error");
            }
        };
        AgentSystem after = createOrderedSystem("After", 20, callOrder);

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(failing, after));
        loop.processMessage(createUserMessage());

        assertEquals(List.of("Failing", "After"), callOrder);
        verify(sessionPort).save(session);
    }

    // ===== FINAL_ANSWER_READY stops loop =====

    @Test
    void loopStopsWhenFinalAnswerReadySet() {
        List<Integer> iterations = new ArrayList<>();

        // System that simulates tool execution + finalizes the turn on first iteration
        AgentSystem toolWithComplete = new AgentSystem() {
            @Override
            public String getName() {
                return "ToolWithComplete";
            }

            @Override
            public int getOrder() {
                return 40;
            }

            @Override
            public AgentContext process(AgentContext context) {
                iterations.add(context.getCurrentIteration());
                // Always simulate tool calls that would normally continue the loop
                LlmResponse response = LlmResponse.builder()
                        .content("")
                        .toolCalls(List.of(
                                Message.ToolCall.builder().id("tc1").name("send_voice").arguments(Map.of()).build()))
                        .build();
                context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
                context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, Boolean.TRUE);
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentLoop loop = createLoop(List.of(toolWithComplete));
        loop.processMessage(createUserMessage());

        assertEquals(1, iterations.size(), "Loop should stop after 1 iteration when FINAL_ANSWER_READY is set");
    }

    // ===== Feedback Guarantee Tests =====

    @Test
    void shouldSendGenericFeedbackWhenNoResponseSent() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentSystem noop = createOrderedSystem("Noop", 10, new ArrayList<>());

        AgentLoop loop = createLoop(List.of(noop));
        loop.processMessage(createUserMessage());

        // Feedback guarantee should send generic feedback
        verify(channelPort).sendMessage(CHAT_ID, GENERIC_FEEDBACK);
    }

    @Test
    void shouldNotSendFeedbackWhenResponseAlreadySent() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentSystem responseSentSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "ResponseSent";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(responseSentSystem));
        loop.processMessage(createUserMessage());

        // No feedback messages should be sent (only response tracking, no channel
        // calls)
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void shouldSendUnsentLlmResponseAsFeedback() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        AgentSystem llmResponseSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "LlmResponse";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                LlmResponse response = LlmResponse.builder().content("Unsent answer").build();
                context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
                // RESPONSE_SENT not set — simulating delivery failure
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(llmResponseSystem));
        loop.processMessage(createUserMessage());

        // Should send the unsent LLM response directly
        verify(channelPort).sendMessage(CHAT_ID, "Unsent answer");
    }

    @Test
    void shouldAttemptLlmInterpretationOnSystemErrors() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);
        when(llmPort.isAvailable()).thenReturn(true);

        LlmResponse interpretedResponse = LlmResponse.builder()
                .content("The service shut down during processing.")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(interpretedResponse));
        when(preferencesService.getMessage(eq(KEY_ERROR_FEEDBACK), any(Object[].class)))
                .thenReturn("Something went wrong during processing: The service shut down during processing.");

        AgentSystem failingSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "Crashing";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                throw new RuntimeException("Process terminated (exit code 143)");
            }
        };

        AgentLoop loop = createLoop(List.of(failingSystem));
        loop.processMessage(createUserMessage());

        // Should call LLM for error interpretation
        verify(llmPort).chat(any());
        // Should send the interpreted error
        verify(channelPort).sendMessage(CHAT_ID,
                "Something went wrong during processing: The service shut down during processing.");
    }

    @Test
    void shouldFallbackToFormattedErrorWhenLlmInterpretationFails() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);
        when(llmPort.isAvailable()).thenReturn(true);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM down")));
        when(preferencesService.getMessage(eq(KEY_ERROR_FEEDBACK), any(Object[].class)))
                .thenReturn("Something went wrong during processing: Process terminated (exit code 143)");

        AgentSystem failingSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "Crashing";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                throw new RuntimeException("Process terminated (exit code 143)");
            }
        };

        AgentLoop loop = createLoop(List.of(failingSystem));
        loop.processMessage(createUserMessage());

        // Should fallback to formatted error since LLM interpretation failed
        verify(channelPort).sendMessage(CHAT_ID,
                "Something went wrong during processing: Process terminated (exit code 143)");
    }

    @Test
    void shouldSkipFeedbackGuaranteeForAutoMode() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        Message autoMessage = Message.builder()
                .id("auto-1")
                .role(ROLE_USER)
                .content("Auto task")
                .channelType(CHANNEL_TYPE_TELEGRAM)
                .chatId(CHAT_ID)
                .senderId("bot")
                .timestamp(FIXED_TIME)
                .metadata(Map.of("auto.mode", true))
                .build();

        AgentSystem noop = createOrderedSystem("Noop", 10, new ArrayList<>());

        AgentLoop loop = createLoop(List.of(noop));
        loop.processMessage(autoMessage);

        // No feedback messages should be sent for auto mode
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void shouldSkipFeedbackWhenNoChannelRegistered() {
        AgentSession session = AgentSession.builder()
                .id("slack:456")
                .channelType("slack")
                .chatId("456")
                .createdAt(FIXED_TIME)
                .updatedAt(FIXED_TIME)
                .build();
        when(sessionPort.getOrCreate("slack", "456")).thenReturn(session);

        Message slackMessage = Message.builder()
                .id("msg-2")
                .role(ROLE_USER)
                .content("Hello")
                .channelType("slack")
                .chatId("456")
                .senderId("user1")
                .timestamp(FIXED_TIME)
                .build();

        // No slack channel registered — feedback should be skipped silently
        AgentLoop loop = createLoop(List.of(createOrderedSystem("Noop", 10, new ArrayList<>())));
        loop.processMessage(slackMessage);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void shouldFallThroughToGenericWhenSendFails() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);

        // First sendMessage call fails, second succeeds (unsent response fails, generic
        // succeeds)
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")))
                .thenReturn(CompletableFuture.completedFuture(null));

        AgentSystem llmResponseSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "LlmResp";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                LlmResponse response = LlmResponse.builder().content("Unsent answer").build();
                context.setAttribute(ContextAttributes.LLM_RESPONSE, response);
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(llmResponseSystem));
        loop.processMessage(createUserMessage());

        // First call with LLM response content fails, falls through to generic
        verify(channelPort, times(2)).sendMessage(eq(CHAT_ID), anyString());
    }

    @Test
    void shouldCollectLlmErrorForFeedback() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);
        when(llmPort.isAvailable()).thenReturn(false);
        when(preferencesService.getMessage(eq(KEY_ERROR_FEEDBACK), any(Object[].class)))
                .thenReturn("Error: LLM timeout");

        AgentSystem llmErrorSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "LlmErr";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.LLM_ERROR, "LLM timeout");
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(llmErrorSystem));
        loop.processMessage(createUserMessage());

        // Should send generic since LLM not available for interpretation
        verify(channelPort).sendMessage(eq(CHAT_ID), anyString());
    }

    @Test
    void shouldCollectRoutingErrorForFeedback() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);
        when(llmPort.isAvailable()).thenReturn(false);

        AgentSystem routingErrorSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "RoutingErr";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.ROUTING_ERROR, "Connection reset");
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(routingErrorSystem));
        loop.processMessage(createUserMessage());

        // Should send generic feedback since no LLM available for interpretation
        verify(channelPort).sendMessage(CHAT_ID, GENERIC_FEEDBACK);
    }

    @Test
    void shouldHandleLlmErrorWithAvailableLlm() {
        AgentSession session = createSession();
        when(sessionPort.getOrCreate(CHANNEL_TYPE_TELEGRAM, CHAT_ID)).thenReturn(session);
        when(llmPort.isAvailable()).thenReturn(true);

        LlmResponse interpretation = LlmResponse.builder()
                .content("The AI model timed out.")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(interpretation));
        when(preferencesService.getMessage(eq(KEY_ERROR_FEEDBACK), any(Object[].class)))
                .thenReturn("Something went wrong: The AI model timed out.");

        AgentSystem llmErrorSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "LlmErr";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                context.setAttribute(ContextAttributes.LLM_ERROR, "Request timed out");
                return context;
            }
        };

        AgentLoop loop = createLoop(List.of(llmErrorSystem));
        loop.processMessage(createUserMessage());

        verify(llmPort).chat(any());
        verify(channelPort).sendMessage(CHAT_ID,
                "Something went wrong: The AI model timed out.");
    }

    // ===== Helpers =====

    private AgentSystem createOrderedSystem(String name, int order, List<String> callOrder) {
        return new AgentSystem() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public AgentContext process(AgentContext context) {
                callOrder.add(name);
                return context;
            }
        };
    }

    private AgentSystem createTrackingSystem(List<Integer> iterations) {
        return new AgentSystem() {
            @Override
            public String getName() {
                return "Tracker";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                iterations.add(context.getCurrentIteration());
                return context;
            }
        };
    }
}
