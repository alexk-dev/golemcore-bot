package me.golemcore.bot.domain.system.toolloop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.ContextBudgetPolicy;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

abstract class DefaultToolLoopSystemFixture {

    static final String MODEL_BALANCED = "gpt-4o";
    static final String TOOL_CALL_ID = "tc-1";
    static final String TOOL_NAME = "test_tool";
    static final String CONTENT_DONE = "Done";
    static final String CONTENT_HELLO = "Hello";
    static final String USER_DENIED = "User denied";

    @Mock
    LlmPort llmPort;

    @Mock
    ToolExecutorPort toolExecutor;

    @Mock
    HistoryWriter historyWriter;

    @Mock
    ConversationViewBuilder viewBuilder;

    @Mock
    PlanService planService;

    @Mock
    ModelSelectionService modelSelectionService;

    @Mock
    RuntimeConfigService runtimeConfigService;

    @Mock
    TurnProgressService turnProgressService;

    BotProperties.TurnProperties turnSettings;

    BotProperties.ToolLoopProperties settings;
    Clock clock;
    DefaultToolLoopSystem system;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clock = Clock.fixed(Instant.parse("2026-02-14T00:00:00Z"), ZoneId.of("UTC"));

        settings = new BotProperties.ToolLoopProperties();
        settings.setStopOnToolFailure(false);
        settings.setStopOnConfirmationDenied(true);
        settings.setStopOnToolPolicyDenied(false);

        when(modelSelectionService.resolveForTier(any())).thenReturn(
                new ModelSelectionService.ModelSelection(MODEL_BALANCED, null));
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);

        when(viewBuilder.buildView(any(), any()))
                .thenReturn(new ConversationView(List.of(), List.of()));

        turnSettings = new BotProperties.TurnProperties();
        system = buildSystem();
    }

    DefaultToolLoopSystem buildSystem() {
        return DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();
    }

    AgentContext buildContext() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .chatId("chat-1")
                .build();

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
    }

    LlmResponse finalResponse(String content) {
        return LlmResponse.builder()
                .content(content)
                .toolCalls(null)
                .build();
    }

    LlmResponse toolCallResponse(List<Message.ToolCall> toolCalls) {
        return LlmResponse.builder()
                .content(null)
                .toolCalls(toolCalls)
                .build();
    }

    Message.ToolCall toolCall(String id, String name) {
        return Message.ToolCall.builder()
                .id(id)
                .name(name)
                .arguments(Map.of("key", "value"))
                .build();
    }

    DefaultToolLoopSystem buildSystemWithRuntimeEvents() {
        RuntimeEventService runtimeEventService = new RuntimeEventService(clock);
        return DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .runtimeEventService(runtimeEventService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();
    }

    DefaultToolLoopSystem buildSystemWithRuntimeConfig() {
        return DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .runtimeConfigService(runtimeConfigService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();
    }

    DefaultToolLoopSystem buildSystemWithTurnProgress() {
        return DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .runtimeConfigService(runtimeConfigService)
                .turnProgressService(turnProgressService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();
    }

    DefaultToolLoopSystem buildSystemWithRecovery(ToolFailureRecoveryService recoveryService) {
        return DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(me.golemcore.bot.support.TestPorts.turn(turnSettings))
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .toolFailureRecoveryService(recoveryService)
                .contextBudgetPolicy(new ContextBudgetPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();
    }

    void stubRuntimeConfigDefaults() {
        when(runtimeConfigService.getTurnMaxLlmCalls()).thenReturn(200);
        when(runtimeConfigService.getTurnMaxToolExecutions()).thenReturn(500);
        when(runtimeConfigService.getTurnDeadline()).thenReturn(Duration.ofHours(1));
    }

    ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmCallPhase.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    static final class DefaultRequestViewBuilder implements ConversationViewBuilder {

        @Override
        public ConversationView buildView(AgentContext context, String targetModel) {
            return ConversationView.ofMessages(context.getMessages());
        }
    }
}
