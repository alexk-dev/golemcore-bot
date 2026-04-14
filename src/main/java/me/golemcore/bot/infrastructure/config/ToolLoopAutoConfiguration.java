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
import me.golemcore.bot.domain.system.toolloop.ToolCallExecutionServiceToolExecutorAdapter;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolFailureRecoveryService;
import me.golemcore.bot.domain.system.toolloop.ToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.UsageTrackingLlmPortDecorator;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.domain.system.toolloop.view.ToolMessageMasker;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.TelemetryRollupPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Spring wiring for ToolLoopSystem (domain orchestrator + ports). */
@Configuration
public class ToolLoopAutoConfiguration {

    @Bean
    public ToolExecutorPort toolExecutorPort(ToolCallExecutionService toolCallExecutionService) {
        // Intentionally keep ToolLoopSystem depending on a stable port.
        // This adapter provides a transitional bridge to the current tool execution
        // wiring.
        return new ToolCallExecutionServiceToolExecutorAdapter(toolCallExecutionService);
    }

    @Bean
    public HistoryWriter toolLoopHistoryWriter(Clock clock) {
        return new DefaultHistoryWriter(clock);
    }

    @Bean
    public ToolMessageMasker toolMessageMasker() {
        return new FlatteningToolMessageMasker();
    }

    @Bean
    public ConversationViewBuilder conversationViewBuilder(ToolMessageMasker toolMessageMasker) {
        return new DefaultConversationViewBuilder(toolMessageMasker);
    }

    @Bean
    public static ContextTokenEstimator contextTokenEstimator() {
        return new ContextTokenEstimator();
    }

    @Bean
    public ContextCompactionPolicy contextCompactionPolicy(
            RuntimeConfigService runtimeConfigService,
            ModelSelectionService modelSelectionService) {
        return new ContextCompactionPolicy(runtimeConfigService, modelSelectionService);
    }

    @Bean
    public ToolLoopSystem toolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutorPort,
            HistoryWriter historyWriter, ConversationViewBuilder viewBuilder, ToolRuntimeSettingsPort settingsPort,
            ModelSelectionService modelSelectionService, PlanService planService,
            RuntimeConfigService runtimeConfigService,
            UsageTrackingPort usageTracker,
            TelemetryRollupPort telemetryRollupPort,
            CompactionOrchestrationService compactionOrchestrationService,
            ContextTokenEstimator contextTokenEstimator,
            ContextCompactionPolicy contextCompactionPolicy,
            RuntimeEventService runtimeEventService,
            TurnProgressService turnProgressService,
            TraceService traceService,
            ToolFailureRecoveryService toolFailureRecoveryService) {
        LlmPort tracked = new UsageTrackingLlmPortDecorator(llmPort, usageTracker, telemetryRollupPort);
        return DefaultToolLoopSystem.builder()
                .llmPort(tracked)
                .toolExecutor(toolExecutorPort)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .turnSettings(settingsPort.turn())
                .settings(settingsPort.toolLoop())
                .modelSelectionService(modelSelectionService)
                .planService(planService)
                .runtimeConfigService(runtimeConfigService)
                .compactionOrchestrationService(compactionOrchestrationService)
                .contextTokenEstimator(contextTokenEstimator)
                .contextCompactionPolicy(contextCompactionPolicy)
                .runtimeEventService(runtimeEventService)
                .turnProgressService(turnProgressService)
                .traceService(traceService)
                .toolFailureRecoveryService(toolFailureRecoveryService)
                .clock(Clock.systemUTC())
                .build();
    }
}
