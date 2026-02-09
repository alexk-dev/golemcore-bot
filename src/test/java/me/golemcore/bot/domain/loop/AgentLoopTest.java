package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
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

    private SessionPort sessionPort;
    private RateLimitPort rateLimiter;
    private BotProperties properties;
    private UserPreferencesService preferencesService;
    private ChannelPort channelPort;
    private Clock clock;

    private static final Instant FIXED_TIME = Instant.parse("2026-01-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        sessionPort = mock(SessionPort.class);
        rateLimiter = mock(RateLimitPort.class);
        properties = new BotProperties();
        properties.getAgent().setMaxIterations(5);
        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(anyString(), any(Object[].class))).thenReturn("limit reached");

        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn("telegram");
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

        when(rateLimiter.tryConsume()).thenReturn(RateLimitResult.allowed(10));
    }

    private AgentLoop createLoop(List<AgentSystem> systems) {
        return new AgentLoop(sessionPort, rateLimiter, properties, systems,
                List.of(channelPort), preferencesService, clock);
    }

    private Message createUserMessage() {
        return Message.builder()
                .id("msg-1")
                .role("user")
                .content("Hello")
                .channelType("telegram")
                .chatId("123")
                .senderId("user1")
                .timestamp(FIXED_TIME)
                .build();
    }

    private AgentSession createSession() {
        return AgentSession.builder()
                .id("telegram:123")
                .channelType("telegram")
                .chatId("123")
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
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

        AgentLoop loop = createLoop(List.of(system30, system10, system20)); // intentionally unordered
        loop.processMessage(createUserMessage());

        assertEquals(List.of("A", "B", "C"), callOrder);
        verify(sessionPort).save(session);
    }

    // ===== Loop continuation with tool calls =====

    @Test
    void loopContinuesWhenToolsExecuted() {
        List<Integer> iterations = new ArrayList<>();

        // System that simulates tool execution on first iteration
        AgentSystem toolSystem = new AgentSystem() {
            @Override
            public String getName() {
                return "ToolSim";
            }

            @Override
            public int getOrder() {
                return 40;
            }

            @Override
            public AgentContext process(AgentContext context) {
                iterations.add(context.getCurrentIteration());
                if (context.getCurrentIteration() == 0) {
                    // Simulate: LLM returned tool calls, tools were executed
                    LlmResponse response = LlmResponse.builder()
                            .content("")
                            .toolCalls(List
                                    .of(Message.ToolCall.builder().id("tc1").name("test").arguments(Map.of()).build()))
                            .build();
                    context.setAttribute("llm.response", response);
                    context.setAttribute("tools.executed", true);
                }
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

        AgentLoop loop = createLoop(List.of(toolSystem));
        loop.processMessage(createUserMessage());

        // Should have run at least 2 iterations (iteration 0 with tool calls, iteration
        // 1 without)
        assertTrue(iterations.size() >= 2, "Expected at least 2 iterations, got " + iterations.size());
        assertEquals(0, iterations.get(0));
        assertEquals(1, iterations.get(1));
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
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

        AgentLoop loop = createLoop(List.of(system));
        loop.processMessage(createUserMessage());

        assertEquals(1, iterations.size(), "Should run exactly 1 iteration when no tools");
    }

    // ===== Max iterations limit =====

    @Test
    void maxIterationsLimitEnforced() {
        properties.getAgent().setMaxIterations(3);
        List<Integer> iterations = new ArrayList<>();

        // System that always triggers another loop iteration
        AgentSystem infiniteTools = new AgentSystem() {
            @Override
            public String getName() {
                return "InfiniteTools";
            }

            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public AgentContext process(AgentContext context) {
                iterations.add(context.getCurrentIteration());
                LlmResponse response = LlmResponse.builder()
                        .content("")
                        .toolCalls(
                                List.of(Message.ToolCall.builder().id("tc1").name("test").arguments(Map.of()).build()))
                        .build();
                context.setAttribute("llm.response", response);
                context.setAttribute("tools.executed", true);
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

        AgentLoop loop = createLoop(List.of(infiniteTools));
        loop.processMessage(createUserMessage());

        assertEquals(3, iterations.size(), "Should stop after max iterations");
        // Verify iteration limit notification sent to channel
        verify(channelPort).sendMessage(eq("123"), eq("limit reached"));
    }

    // ===== Rate limiting =====

    @Test
    void rateLimitBlocksMessage() {
        when(rateLimiter.tryConsume())
                .thenReturn(RateLimitResult.denied(1000, "Rate limit exceeded"));

        AgentSession session = createSession();
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

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
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

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

        verify(sessionPort).getOrCreate("telegram", "123");
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
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

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
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

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
                .role("user")
                .content("Auto check")
                .channelType("telegram")
                .chatId("123")
                .senderId("bot")
                .timestamp(FIXED_TIME)
                .metadata(Map.of("auto.mode", true))
                .build();

        AgentSession session = createSession();
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

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
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

        AgentLoop loop = createLoop(List.of(failing, after));
        loop.processMessage(createUserMessage());

        assertEquals(List.of("Failing", "After"), callOrder);
        verify(sessionPort).save(session);
    }

    // ===== loop.complete stops loop =====

    @Test
    void loopStopsWhenLoopCompleteSet() {
        List<Integer> iterations = new ArrayList<>();

        // System that simulates tool execution + sets loop.complete on first iteration
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
                context.setAttribute("llm.response", response);
                context.setAttribute("tools.executed", true);
                context.setAttribute("loop.complete", true);
                return context;
            }
        };

        AgentSession session = createSession();
        when(sessionPort.getOrCreate("telegram", "123")).thenReturn(session);

        AgentLoop loop = createLoop(List.of(toolWithComplete));
        loop.processMessage(createUserMessage());

        assertEquals(1, iterations.size(), "Loop should stop after 1 iteration when loop.complete is set");
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
