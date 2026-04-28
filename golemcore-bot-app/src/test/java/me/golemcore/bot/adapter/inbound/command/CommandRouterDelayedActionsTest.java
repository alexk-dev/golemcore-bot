package me.golemcore.bot.adapter.inbound.command;

import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedActionStatus;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.auto.AutoModeService;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.scheduling.DelayedActionPolicyService;
import me.golemcore.bot.domain.scheduling.DelayedSessionActionService;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.scheduling.ScheduleService;
import me.golemcore.bot.domain.session.SessionRunCoordinator;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandRouterDelayedActionsTest {

    @Test
    void shouldListDelayedActions() throws Exception {
        UserPreferencesService preferencesService = createPreferencesService(Map.of(
                "command.later.list.title", "Delayed follow-ups ({0})",
                "command.later.list.empty", "No delayed follow-ups for this session.",
                "command.later.list.next-check", "Next check",
                "command.later.list.status", "Status",
                "command.later.list.cancel-on-activity", "Cancels on new activity",
                "command.later.status.scheduled", "scheduled"));
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        when(delayedActionService.listActions("telegram", "conv-1")).thenReturn(List.of(
                DelayedSessionAction.builder()
                        .id("delay-1")
                        .kind(DelayedActionKind.REMIND_LATER)
                        .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                        .status(DelayedActionStatus.SCHEDULED)
                        .runAt(Instant.parse("2026-03-19T18:35:00Z"))
                        .payload(Map.of(
                                "humanSummary", "Reminder: ping me",
                                "userVisibleKind", "reminder"))
                        .build()));

        CommandRouter router = createRouter(preferencesService, delayedActionPolicyService, delayedActionService);

        CommandPort.CommandResult result = router.execute("later", List.of("list"), Map.of(
                "sessionId", "telegram:conv-1",
                "channelType", "telegram",
                "chatId", "transport-1",
                "sessionChatId", "transport-1",
                "conversationKey", "conv-1")).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("delay-1"));
        assertTrue(result.output().contains("Reminder: ping me"));
        assertTrue(result.output().contains("scheduled"));
        assertTrue(result.output().contains("2026-03-19 14:35 EDT"));
        assertFalse(result.output().contains("[REMIND_LATER]"));
        assertFalse(result.output().contains("SCHEDULED"));
        verify(delayedActionService).listActions("telegram", "conv-1");
    }

    @Test
    void shouldHideDelayedActionsToolFromWebhookToolsList() throws Exception {
        UserPreferencesService preferencesService = createPreferencesService(Map.of());
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        when(delayedActionPolicyService.canScheduleActions("webhook")).thenReturn(false);

        ToolComponent delayedTool = mock(ToolComponent.class);
        when(delayedTool.isEnabled()).thenReturn(true);
        when(delayedTool.getToolName()).thenReturn(ScheduleSessionActionTool.TOOL_NAME);
        when(delayedTool.getDefinition()).thenReturn(me.golemcore.bot.domain.model.ToolDefinition.builder()
                .name(ScheduleSessionActionTool.TOOL_NAME)
                .description("Delayed actions")
                .build());

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        AutomationCommandService automationCommandService = new AutomationCommandService(
                mock(AutoModeService.class),
                runtimeConfigService,
                mock(ScheduleService.class),
                delayedActionPolicyService,
                null);
        AutomationCommandHandler automationCommandHandler = new AutomationCommandHandler(
                automationCommandService,
                preferencesService,
                mock(ApplicationEventPublisher.class));
        ModelSelectionCommandHandler modelSelectionCommandHandler = new ModelSelectionCommandHandler(
                mock(ModelSelectionCommandService.class),
                preferencesService);
        PlanCommandHandler planCommandHandler = new PlanCommandHandler(
                new PlanCommandService(mock(PlanService.class)),
                preferencesService);

        CommandRouter router = new CommandRouter(
                mock(SkillComponent.class),
                List.of(delayedTool),
                mock(SessionPort.class),
                mock(UsageTrackingPort.class),
                preferencesService,
                mock(CompactionOrchestrationService.class),
                automationCommandHandler,
                modelSelectionCommandHandler,
                planCommandHandler,
                delayedActionPolicyService,
                mock(SessionRunCoordinator.class),
                mock(ObjectProvider.class));

        CommandPort.CommandResult result = router.execute("tools", List.of(), Map.of(
                "sessionId", "webhook:conv-1",
                "channelType", "webhook",
                "chatId", "conv-1")).get();

        assertTrue(result.success());
        assertTrue(!result.output().contains(ScheduleSessionActionTool.TOOL_NAME));
    }

    @Test
    void shouldExplainLaterHelpAsRemindersAndCheckBacks() throws Exception {
        UserPreferencesService preferencesService = createPreferencesService(Map.of(
                "command.later.help.text",
                "Use /later to manage reminders and wait-for-result follow-ups.\n"
                        + "/later list - show reminders and background checks for this session\n"
                        + "/later cancel <action_id> - cancel a reminder or follow-up\n"
                        + "/later now <action_id> - trigger a delayed check immediately\n"
                        + "Examples:\n"
                        + "- remind me in an hour\n"
                        + "- check back when the export is ready"));
        CommandRouter router = createRouter(preferencesService, mock(DelayedActionPolicyService.class),
                mock(DelayedSessionActionService.class));

        CommandPort.CommandResult result = router.execute("later", List.of("help"), Map.of(
                "sessionId", "telegram:conv-1",
                "channelType", "telegram",
                "chatId", "transport-1",
                "sessionChatId", "transport-1",
                "conversationKey", "conv-1")).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("reminders"));
        assertTrue(result.output().contains("wait-for-result"));
        assertTrue(result.output().contains("check back when the export is ready"));
    }

    @Test
    void shouldExposeLaterInHelpAsProductFeature() throws Exception {
        UserPreferencesService preferencesService = createPreferencesService(Map.of(
                "command.help.text",
                "Available commands:\n/later - Reminders and follow-ups"));
        CommandRouter router = createRouter(preferencesService, mock(DelayedActionPolicyService.class),
                mock(DelayedSessionActionService.class));

        CommandPort.CommandResult result = router.execute("help", List.of(), Map.of(
                "sessionId", "telegram:conv-1",
                "channelType", "telegram",
                "chatId", "transport-1")).get();

        assertTrue(result.success());
        assertTrue(result.output().contains("/later - Reminders and follow-ups"));
    }

    private static CommandRouter createRouter(UserPreferencesService preferencesService,
            DelayedActionPolicyService delayedActionPolicyService,
            DelayedSessionActionService delayedActionService) {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        if (delayedActionPolicyService != null) {
            when(delayedActionPolicyService.canScheduleActions("telegram")).thenReturn(true);
        }
        AutomationCommandService automationCommandService = new AutomationCommandService(
                mock(AutoModeService.class),
                runtimeConfigService,
                mock(ScheduleService.class),
                delayedActionPolicyService,
                delayedActionService);
        AutomationCommandHandler automationCommandHandler = new AutomationCommandHandler(
                automationCommandService,
                preferencesService,
                mock(ApplicationEventPublisher.class));
        ModelSelectionCommandHandler modelSelectionCommandHandler = new ModelSelectionCommandHandler(
                mock(ModelSelectionCommandService.class),
                preferencesService);
        PlanCommandHandler planCommandHandler = new PlanCommandHandler(
                new PlanCommandService(mock(PlanService.class)),
                preferencesService);

        return new CommandRouter(
                mock(SkillComponent.class),
                List.<ToolComponent>of(),
                mock(SessionPort.class),
                mock(UsageTrackingPort.class),
                preferencesService,
                mock(CompactionOrchestrationService.class),
                automationCommandHandler,
                modelSelectionCommandHandler,
                planCommandHandler,
                delayedActionPolicyService,
                mock(SessionRunCoordinator.class),
                mock(ObjectProvider.class));
    }

    private static UserPreferencesService createPreferencesService(Map<String, String> messages) {
        UserPreferencesService preferencesService = mock(UserPreferencesService.class, invocation -> {
            if ("getMessage".equals(invocation.getMethod().getName())) {
                String key = (String) invocation.getArguments()[0];
                String template = messages.getOrDefault(key, key);
                Object[] args = invocation.getArguments().length > 1 && invocation.getArguments()[1] instanceof Object[]
                        ? (Object[]) invocation.getArguments()[1]
                        : new Object[0];
                return MessageFormat.format(template, args);
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(preferencesService.getPreferences()).thenReturn(UserPreferences.builder()
                .timezone("America/New_York")
                .build());
        return preferencesService;
    }
}
