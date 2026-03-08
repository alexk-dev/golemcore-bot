package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.RuntimeEventService;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder;
import me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker;
import me.golemcore.bot.domain.system.toolloop.view.ToolMessageMasker;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ToolLoopConfigurationTest {

    private final ToolLoopConfiguration configuration = new ToolLoopConfiguration();

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
        CompactionOrchestrationService compactionOrchestrationService = mock(CompactionOrchestrationService.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);

        ToolLoopSystem system = configuration.toolLoopSystem(
                llmPort,
                toolExecutorPort,
                historyWriter,
                viewBuilder,
                botProperties,
                modelSelectionService,
                planService,
                runtimeConfigService,
                usageTrackingPort,
                compactionOrchestrationService,
                runtimeEventService);

        assertNotNull(system);
        assertInstanceOf(DefaultToolLoopSystem.class, system);
    }
}
