package me.golemcore.bot.adapter.inbound.command;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedActionStatus;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandRouterDelayedActionsTest {

    @Test
    void shouldListDelayedActions() throws Exception {
        UserPreferencesService preferencesService = mock(UserPreferencesService.class, invocation -> {
            if ("getMessage".equals(invocation.getMethod().getName())) {
                return invocation.getArguments()[0];
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        when(delayedActionService.listActions("telegram", "conv-1")).thenReturn(List.of(
                DelayedSessionAction.builder()
                        .id("delay-1")
                        .kind(DelayedActionKind.REMIND_LATER)
                        .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                        .status(DelayedActionStatus.SCHEDULED)
                        .runAt(Instant.parse("2026-03-19T18:35:00Z"))
                        .build()));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);

        CommandRouter router = new CommandRouter(
                mock(SkillComponent.class),
                List.<ToolComponent>of(),
                mock(SessionPort.class),
                mock(UsageTrackingPort.class),
                preferencesService,
                mock(CompactionOrchestrationService.class),
                mock(AutoModeService.class),
                mock(ModelSelectionService.class),
                mock(PlanService.class),
                mock(PlanExecutionService.class),
                mock(ScheduleService.class),
                delayedActionPolicyService,
                delayedActionService,
                mock(SessionRunCoordinator.class),
                mock(ApplicationEventPublisher.class),
                runtimeConfigService,
                mock(ObjectProvider.class));

        CommandPort.CommandResult result = router.execute("later", List.of("list"), Map.of(
                "sessionId", "telegram:conv-1",
                "channelType", "telegram",
                "chatId", "transport-1",
                "sessionChatId", "transport-1",
                "conversationKey", "conv-1")).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("delay-1"));
        verify(delayedActionService).listActions("telegram", "conv-1");
    }

    @Test
    void shouldHideDelayedActionsToolFromWebhookToolsList() throws Exception {
        UserPreferencesService preferencesService = mock(UserPreferencesService.class, invocation -> {
            if ("getMessage".equals(invocation.getMethod().getName())) {
                return invocation.getArguments()[0];
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        when(delayedActionPolicyService.canScheduleActions("webhook")).thenReturn(false);

        ToolComponent delayedTool = mock(ToolComponent.class);
        when(delayedTool.isEnabled()).thenReturn(true);
        when(delayedTool.getToolName()).thenReturn(ScheduleSessionActionTool.TOOL_NAME);
        when(delayedTool.getDefinition()).thenReturn(me.golemcore.bot.domain.model.ToolDefinition.builder()
                .name(ScheduleSessionActionTool.TOOL_NAME)
                .description("Delayed actions")
                .build());

        CommandRouter router = new CommandRouter(
                mock(SkillComponent.class),
                List.of(delayedTool),
                mock(SessionPort.class),
                mock(UsageTrackingPort.class),
                preferencesService,
                mock(CompactionOrchestrationService.class),
                mock(AutoModeService.class),
                mock(ModelSelectionService.class),
                mock(PlanService.class),
                mock(PlanExecutionService.class),
                mock(ScheduleService.class),
                delayedActionPolicyService,
                null,
                mock(SessionRunCoordinator.class),
                mock(ApplicationEventPublisher.class),
                mock(RuntimeConfigService.class),
                mock(ObjectProvider.class));

        CommandPort.CommandResult result = router.execute("tools", List.of(), Map.of(
                "sessionId", "webhook:conv-1",
                "channelType", "webhook",
                "chatId", "conv-1")).get();

        assertTrue(result.success());
        assertTrue(!result.output().contains(ScheduleSessionActionTool.TOOL_NAME));
    }
}
