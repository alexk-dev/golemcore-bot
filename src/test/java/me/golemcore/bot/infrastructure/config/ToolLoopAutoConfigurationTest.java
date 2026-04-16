package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.HistoryWriter;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolFailureRecoveryService;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolCallExecutionServiceToolExecutorAdapter;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.domain.system.toolloop.view.ToolMessageMasker;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.port.outbound.TelemetryRollupPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ToolLoopAutoConfigurationTest {

    private final ToolLoopAutoConfiguration configuration = new ToolLoopAutoConfiguration();

    @Test
    void shouldCreateToolExecutorPortAdapter() {
        ToolCallExecutionService executionService = mock(ToolCallExecutionService.class);

        ToolExecutorPort port = configuration.toolExecutorPort(executionService);

        assertNotNull(port);
        assertInstanceOf(ToolCallExecutionServiceToolExecutorAdapter.class, port);
    }

    @Test
    void shouldCreateHistoryWriter() {
        HistoryWriter historyWriter = configuration.toolLoopHistoryWriter(java.time.Clock.systemUTC());

        assertNotNull(historyWriter);
        assertInstanceOf(DefaultHistoryWriter.class, historyWriter);
    }

    @Test
    void shouldCreateConversationViewComponents() {
        ToolMessageMasker masker = configuration.toolMessageMasker();
        ConversationViewBuilder builder = configuration.conversationViewBuilder(masker);

        assertInstanceOf(FlatteningToolMessageMasker.class, masker);
        assertInstanceOf(DefaultConversationViewBuilder.class, builder);
    }

    @Test
    void shouldCreateDefaultToolLoopSystem() {
        LlmPort llmPort = mock(LlmPort.class);
        ToolExecutorPort toolExecutorPort = mock(ToolExecutorPort.class);
        HistoryWriter historyWriter = mock(HistoryWriter.class);
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        BotProperties botProperties = new BotProperties();
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        PlanService planService = mock(PlanService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        UsageTrackingPort usageTrackingPort = mock(UsageTrackingPort.class);
        TelemetryRollupPort telemetryRollupStore = mock(TelemetryRollupPort.class);
        CompactionOrchestrationService compactionOrchestrationService = mock(CompactionOrchestrationService.class);
        ContextTokenEstimator contextTokenEstimator = mock(ContextTokenEstimator.class);
        ContextCompactionPolicy contextCompactionPolicy = mock(ContextCompactionPolicy.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        TurnProgressService turnProgressService = mock(TurnProgressService.class);
        TraceService traceService = mock(TraceService.class);
        ToolFailureRecoveryService toolFailureRecoveryService = mock(ToolFailureRecoveryService.class);

        ToolLoopSystem system = configuration.toolLoopSystem(
                llmPort,
                toolExecutorPort,
                historyWriter,
                viewBuilder,
                me.golemcore.bot.support.TestPorts.settings(botProperties),
                modelSelectionService,
                planService,
                runtimeConfigService,
                usageTrackingPort,
                telemetryRollupStore,
                compactionOrchestrationService,
                contextTokenEstimator,
                contextCompactionPolicy,
                runtimeEventService,
                turnProgressService,
                traceService,
                toolFailureRecoveryService,
                null);

        assertNotNull(system);
        assertInstanceOf(DefaultToolLoopSystem.class, system);
    }
}
