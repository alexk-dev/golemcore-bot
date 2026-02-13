package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import me.golemcore.bot.infrastructure.config.BotProperties;

/** Spring wiring for ToolLoopSystem (domain orchestrator + ports). */
@Configuration
public class ToolLoopConfiguration {

    @Bean
    public ToolExecutorPort toolExecutorPort(ToolCallExecutionService toolCallExecutionService) {
        // Intentionally keep ToolLoopSystem depending on a stable port.
        // This adapter provides a transitional bridge to the current tool execution
        // wiring.
        return new ToolExecutionSystemToolExecutorAdapter(toolCallExecutionService);
    }

    @Bean
    public HistoryWriter toolLoopHistoryWriter(Clock clock) {
        return new DefaultHistoryWriter(clock);
    }

    @Bean
    public ToolLoopSystem toolLoopSystem(LlmPort llmPort, ToolExecutorPort toolExecutorPort,
            HistoryWriter historyWriter, BotProperties botProperties) {
        return new DefaultToolLoopSystem(llmPort, toolExecutorPort, historyWriter,
                botProperties.getToolLoop(), botProperties.getRouter());
    }
}