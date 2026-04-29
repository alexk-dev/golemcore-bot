package me.golemcore.bot.domain.system.toolloop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.progress.TurnProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

abstract class LlmRequestPreflightPhaseFixture {

    static final Instant NOW = Instant.parse("2026-04-14T00:00:00Z");

    ModelSelectionService modelSelectionService;
    RuntimeConfigService runtimeConfigService;
    CompactionOrchestrationService compactionService;
    RuntimeEventService runtimeEventService;
    TurnProgressService turnProgressService;
    ContextCompactionPolicy contextCompactionPolicy;
    LlmRequestPreflightPhase phase;
    private final List<AttachedAppender> attachedAppenders = new ArrayList<>();

    @BeforeEach
    void setUpPreflightPhase() {
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        compactionService = mock(CompactionOrchestrationService.class);
        runtimeEventService = new RuntimeEventService(Clock.fixed(NOW, ZoneOffset.UTC));
        turnProgressService = mock(TurnProgressService.class);
        when(runtimeConfigService.getCompactionTriggerMode()).thenReturn("model_ratio");
        when(runtimeConfigService.getCompactionModelThresholdRatio()).thenReturn(0.8d);
        when(runtimeConfigService.getCompactionMaxContextTokens()).thenReturn(10_000);
        when(runtimeConfigService.getCompactionKeepLastMessages()).thenReturn(2);
        when(runtimeConfigService.isCompactionEnabled()).thenReturn(true);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1_000);
        contextCompactionPolicy = new ContextCompactionPolicy(runtimeConfigService, modelSelectionService);
        ContextCompactionCoordinator compactionCoordinator = new ContextCompactionCoordinator(
                contextCompactionPolicy,
                compactionService,
                runtimeEventService,
                turnProgressService);
        phase = new LlmRequestPreflightPhase(
                new ContextTokenEstimator(),
                contextCompactionPolicy,
                compactionCoordinator);
    }

    @AfterEach
    void detachAppenders() {
        for (AttachedAppender attached : attachedAppenders) {
            attached.logger().detachAppender(attached.appender());
            attached.appender().stop();
        }
        attachedAppenders.clear();
    }

    ListAppender<ILoggingEvent> attachPreflightLogAppender() {
        return attachAppender((Logger) LoggerFactory.getLogger(ContextCompactionPolicy.class));
    }

    AgentContext buildContext(int messages) {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        for (int index = 0; index < messages; index++) {
            session.addMessage(Message.builder()
                    .role(index % 2 == 0 ? "user" : "assistant")
                    .content("message-" + index)
                    .timestamp(NOW)
                    .build());
        }
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();
    }

    private ListAppender<ILoggingEvent> attachAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        attachedAppenders.add(new AttachedAppender(logger, appender));
        return appender;
    }

    private record AttachedAppender(Logger logger, ListAppender<ILoggingEvent> appender) {
    }
}
