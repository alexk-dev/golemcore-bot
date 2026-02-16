package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.ToolMessageMasker;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Spring wiring for ToolLoopSystem (domain orchestrator + ports). */
@Configuration
public class ToolLoopConfiguration {

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
    public ToolLoopSystem toolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutorPort,
            HistoryWriter historyWriter, ConversationViewBuilder viewBuilder, BotProperties botProperties,
            ModelSelectionService modelSelectionService, PlanService planService,
            UsageTrackingPort usageTracker) {
        LlmPort tracked = new UsageTrackingLlmPortDecorator(llmPort, usageTracker);
        return new DefaultToolLoopSystem(tracked, toolExecutorPort, historyWriter, viewBuilder,
                botProperties.getTurn(), botProperties.getToolLoop(), modelSelectionService, planService);
    }
}
