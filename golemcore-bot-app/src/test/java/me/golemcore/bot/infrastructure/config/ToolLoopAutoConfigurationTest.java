package me.golemcore.bot.infrastructure.config;

import java.util.List;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.tools.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.progress.TurnProgressService;
import me.golemcore.bot.domain.tools.execution.ToolCallExecutionService;
import me.golemcore.bot.domain.tools.registry.ToolRegistryService;
import me.golemcore.bot.domain.system.toolloop.ContextCompactionCoordinator;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.HistoryWriter;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolFailureRecoveryService;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolCallExecutionServiceToolExecutorAdapter;
import me.golemcore.bot.domain.system.toolloop.view.ContextBudgetResolver;
import me.golemcore.bot.domain.system.toolloop.view.ContextWindowProjector;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.domain.system.toolloop.view.HygieneConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.ToolMessageMasker;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.port.outbound.TelemetryRollupPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolLoopAutoConfigurationTest {

    private final ToolLoopAutoConfiguration configuration = new ToolLoopAutoConfiguration();

    @Test
    void shouldCreateToolRegistryService() {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.getToolName()).thenReturn("test_tool");

        ToolRegistryService registry = configuration.toolRegistryService(List.of(tool));

        assertNotNull(registry);
    }

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
        ContextWindowProjector projector = mock(ContextWindowProjector.class);
        ContextBudgetResolver budgetResolver = mock(ContextBudgetResolver.class);
        ConversationViewBuilder builder = configuration.conversationViewBuilder(masker, projector, budgetResolver);

        assertInstanceOf(FlatteningToolMessageMasker.class, masker);
        assertInstanceOf(HygieneConversationViewBuilder.class, builder);
    }

    @Test
    void shouldCreateDefaultToolLoopSystem() {
        LlmPort llmPort = mock(LlmPort.class);
        ToolExecutorPort toolExecutorPort = mock(ToolExecutorPort.class);
        HistoryWriter historyWriter = mock(HistoryWriter.class);
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        BotProperties botProperties = new BotProperties();
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        UsageTrackingPort usageTrackingPort = mock(UsageTrackingPort.class);
        TelemetryRollupPort telemetryRollupStore = mock(TelemetryRollupPort.class);
        CompactionOrchestrationService compactionOrchestrationService = mock(CompactionOrchestrationService.class);
        ContextTokenEstimator contextTokenEstimator = mock(ContextTokenEstimator.class);
        ContextCompactionPolicy contextCompactionPolicy = mock(ContextCompactionPolicy.class);
        ContextCompactionCoordinator contextCompactionCoordinator = mock(ContextCompactionCoordinator.class);
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
                runtimeConfigService,
                usageTrackingPort,
                telemetryRollupStore,
                compactionOrchestrationService,
                contextTokenEstimator,
                contextCompactionPolicy,
                contextCompactionCoordinator,
                runtimeEventService,
                turnProgressService,
                traceService,
                toolFailureRecoveryService,
                mock(PlanModeToolRestrictionService.class),
                null);

        assertNotNull(system);
        assertInstanceOf(DefaultToolLoopSystem.class, system);
    }
}
