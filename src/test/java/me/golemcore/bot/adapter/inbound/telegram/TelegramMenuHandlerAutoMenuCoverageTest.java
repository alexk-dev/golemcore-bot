package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.TelegramSessionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramMenuHandlerAutoMenuCoverageTest {

    private static final String CHAT_ID = "100";
    private static final Integer MESSAGE_ID = 42;

    private TelegramMenuHandler handler;
    private RuntimeConfigService runtimeConfigService;
    private UserPreferencesService preferencesService;
    private AutoModeService autoModeService;
    private PlanService planService;
    private ScheduleService scheduleService;
    private TelegramSessionService telegramSessionService;
    private TelegramClient telegramClient;
    private CommandPort commandRouter;

    @BeforeEach
    void setUp() throws Exception {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        telegramProps.setToken("test-token");
        when(properties.getChannels()).thenReturn(Map.of("telegram", telegramProps));

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.getTelegramToken()).thenReturn("test-token");

        preferencesService = mock(UserPreferencesService.class);
        UserPreferences prefs = UserPreferences.builder().language("en").build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        when(preferencesService.getLanguage()).thenReturn("en");
        when(preferencesService.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preferencesService.getMessage(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preferencesService.getMessage(anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(anyString()))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-5.1", null));

        autoModeService = mock(AutoModeService.class);
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        planService = mock(PlanService.class);
        when(planService.isFeatureEnabled()).thenReturn(false);

        scheduleService = mock(ScheduleService.class);

        telegramSessionService = mock(TelegramSessionService.class);
        when(telegramSessionService.resolveActiveConversationKey(CHAT_ID)).thenReturn("conv-1");
        when(telegramSessionService.listRecentSessions(CHAT_ID, 5)).thenReturn(List.of(
                AgentSession.builder().id("telegram:conv-1").channelType("telegram").chatId("conv-1")
                        .messages(List.of()).build()));

        MessageService messageService = mock(MessageService.class);
        when(messageService.getLanguageDisplayName("en")).thenReturn("English");

        commandRouter = mock(CommandPort.class);

        telegramClient = mock(TelegramClient.class);
        Message sent = mock(Message.class);
        when(sent.getMessageId()).thenReturn(77);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);
        when(telegramClient.execute(any(EditMessageText.class))).thenReturn(mock(Serializable.class));
        when(telegramClient.execute(any(DeleteMessage.class))).thenReturn(true);

        handler = new TelegramMenuHandler(
                properties,
                runtimeConfigService,
                preferencesService,
                modelSelectionService,
                autoModeService,
                planService,
                scheduleService,
                telegramSessionService,
                messageService,
                new TestObjectProvider<>(commandRouter));
        handler.setTelegramClient(telegramClient);

        stubAutoData();
        stubPlanData();
    }

    @Test
    void shouldCoverAutoMenuCallbackBranches() {
        handler.sendMainMenu(CHAT_ID);

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalsNext");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalsPrev");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goal:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDone:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDeleteConfirm:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDelete:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDaily:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalSchedule:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schFreq:daily");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schTime:0900");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schLimit:3");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schSave");

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasks");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasksNext");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasksPrev");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:task:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:ip");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:done");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:fail");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:skip");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:pending");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDeleteConfirm:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDelete:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDaily:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSchedule:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schFreq:weekly");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schDay:1");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schDaysDone");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schTime:1200");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schLimit:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schSave");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:bad");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:unknown");

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schedules");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schedulesNext");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schedulesPrev");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:scheduleDelConfirm:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:scheduleDel:0");

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:createGoal");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:createStandaloneTask");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schCancel");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:refresh");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:noop");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goal:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:task:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:scheduleDel:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:unknown");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:back");

        verify(autoModeService).completeGoal("g1");
        verify(autoModeService).deleteGoal("g1");
        verify(autoModeService, atLeast(5)).updateTaskStatus(eq("g1"), eq("t1"), any(), eq(null));
        verify(autoModeService).deleteTask("g1", "t1");
        verify(scheduleService, atLeast(2)).createSchedule(any(), anyString(), anyString(), anyInt());
        verify(scheduleService).deleteSchedule("s1");
    }

    @Test
    void shouldHandleSendMainMenuWithoutClient() {
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(false);

        handler.sendMainMenu(CHAT_ID);
    }

    @Test
    void shouldHandleSendMainMenuFailure() throws Exception {
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new RuntimeException("send fail"));

        handler.sendMainMenu(CHAT_ID);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void shouldHandleSendSessionsMenuWithoutClient() {
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(false);

        handler.sendSessionsMenu(CHAT_ID);
    }

    @Test
    void shouldHandleSendSessionsMenuFailure() throws Exception {
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new RuntimeException("send fail"));

        handler.sendSessionsMenu(CHAT_ID);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void shouldCoverLanguageMenuLabelsForRussian() {
        when(preferencesService.getLanguage()).thenReturn("ru");

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:lang");
    }

    @Test
    void shouldHandleGoalActionFallbackBranches() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDone:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDeleteConfirm:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDelete:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDaily:99");
    }

    @Test
    void shouldHandleGoalOperationFailures() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");

        doThrow(new IllegalStateException("complete fail"))
                .when(autoModeService)
                .completeGoal("g1");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDone:0");

        doThrow(new IllegalStateException("delete fail"))
                .when(autoModeService)
                .deleteGoal("g1");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDelete:0");
    }

    @Test
    void shouldHandleTaskActionFallbackBranches() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasks");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDeleteConfirm:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDelete:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDaily:99");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:99:done");
    }

    @Test
    void shouldRenderTaskDetailsWithDescriptionAndResult() throws Exception {
        Goal goal = Goal.builder()
                .id("gd")
                .title("Goal details")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id("td")
                        .goalId("gd")
                        .title("Task details")
                        .description("Task description")
                        .result("Task result")
                        .status(AutoTask.TaskStatus.COMPLETED)
                        .order(1)
                        .build()))
                .build();
        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(autoModeService.getGoal("gd")).thenReturn(Optional.of(goal));

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasks");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:task:0");

        org.mockito.ArgumentCaptor<EditMessageText> captor = org.mockito.ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient, atLeast(1)).execute(captor.capture());
        boolean hasTaskTitle = captor.getAllValues().stream()
                .map(EditMessageText::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(textValue -> textValue.contains("Task details"));
        boolean hasGoalTitle = captor.getAllValues().stream()
                .map(EditMessageText::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(textValue -> textValue.contains("Goal details"));
        org.junit.jupiter.api.Assertions.assertTrue(hasTaskTitle);
        org.junit.jupiter.api.Assertions.assertTrue(hasGoalTitle);
    }

    @Test
    void shouldHandleScheduleDeleteConfirmFallback() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schedules");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:scheduleDelConfirm:99");
    }

    @Test
    void shouldHandleInvalidParseIndicesAndUnknownTaskStatus() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDaily:abc");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:weird");
    }

    @Test
    void shouldHandleCommandSectionsAndCommandFailures() {
        when(commandRouter.execute(anyString(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(CommandPort.CommandResult.success("ok")));

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:status");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:skills");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:tools");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:help");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:compact");

        when(commandRouter.execute(anyString(), any(), any())).thenThrow(new RuntimeException("cmd fail"));
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:status");

        verify(commandRouter, atLeast(5)).execute(anyString(), any(), any());
    }

    @Test
    void shouldCoverSessionsEmptyBranch() {
        when(telegramSessionService.listRecentSessions(CHAT_ID, 5)).thenReturn(List.of());

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions");
    }

    @Test
    void shouldResendPersistentMenuBySendingNewMenuWhenEditFails() throws Exception {
        when(commandRouter.execute(anyString(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(CommandPort.CommandResult.success("ok")));
        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(new RuntimeException("edit fail"));

        handler.sendMainMenu(CHAT_ID);
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:status");

        verify(telegramClient, atLeast(2)).execute(any(SendMessage.class));
    }

    @Test
    void shouldCoverSendMenusWithoutClientOnLocalHandler() {
        RuntimeConfigService disabledRuntime = mock(RuntimeConfigService.class);
        when(disabledRuntime.isTelegramEnabled()).thenReturn(false);

        TelegramMenuHandler localHandler = new TelegramMenuHandler(
                mock(BotProperties.class),
                disabledRuntime,
                preferencesService,
                mock(ModelSelectionService.class),
                autoModeService,
                planService,
                scheduleService,
                telegramSessionService,
                mock(MessageService.class),
                new TestObjectProvider<>(mock(CommandPort.class)));

        localHandler.sendMainMenu(CHAT_ID);
        localHandler.sendSessionsMenu(CHAT_ID);
    }

    @Test
    void shouldHandleMainAutoPlanCloseAndUnknownSections() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(any())).thenReturn(false);

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:main");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:auto");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:plan");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:planMenu");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:planMenu:on");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:planMenu:off");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:planMenu:done");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:planMenu:refresh");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:planMenu:back");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:unknown-section");

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:close");

        when(telegramClient.execute(any(DeleteMessage.class))).thenThrow(new RuntimeException("delete fail"));
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:close");

        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(new RuntimeException("edit fail"));
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:close");
    }

    @Test
    void shouldHandleSessionsMenuBranches() {
        handler.sendMainMenu(CHAT_ID);

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions:new");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions:switch:0");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions:switch:x");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions:switch:999");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions:back");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions:unknown");
    }

    @Test
    void shouldCoverResolveSessionTitleFallbackAndTruncateBranches() {
        when(telegramSessionService.listRecentSessions(CHAT_ID, 5)).thenReturn(List.of(
                AgentSession.builder().id("telegram:conv-empty").channelType("telegram").chatId("conv-empty")
                        .messages(List.of()).build(),
                AgentSession.builder().id("telegram:conv-no-user").channelType("telegram").chatId("conv-no-user")
                        .messages(List.of(
                                me.golemcore.bot.domain.model.Message.builder().role("assistant").content("a").build(),
                                me.golemcore.bot.domain.model.Message.builder().role("user").content(" ").build()))
                        .build(),
                AgentSession.builder().id("telegram:conv-user-long").channelType("telegram").chatId("conv-user-long")
                        .messages(List.of(
                                me.golemcore.bot.domain.model.Message.builder().role("user")
                                        .content(
                                                "This is a very long user prompt that should be truncated in session title rendering")
                                        .build()))
                        .build()));

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions");
    }

    @Test
    void shouldHandleAutoMenuWhenFeatureDisabled() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");

        verify(telegramClient, atLeast(1)).execute(any(EditMessageText.class));
    }

    @Test
    void shouldCoverGetOrCreateClientBranches() throws Exception {
        BotProperties properties = mock(BotProperties.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.isTelegramEnabled()).thenReturn(false);

        TelegramMenuHandler localHandler = new TelegramMenuHandler(
                properties,
                runtime,
                preferencesService,
                mock(ModelSelectionService.class),
                autoModeService,
                mock(PlanService.class),
                scheduleService,
                telegramSessionService,
                mock(MessageService.class),
                new TestObjectProvider<>(mock(CommandPort.class)));

        Method method = TelegramMenuHandler.class.getDeclaredMethod("getOrCreateClient");
        method.setAccessible(true);

        Object disabled = method.invoke(localHandler);
        assertNull(disabled);

        when(runtime.isTelegramEnabled()).thenReturn(true);
        when(runtime.getTelegramToken()).thenReturn(" ");
        Object blankToken = method.invoke(localHandler);
        assertNull(blankToken);

        when(runtime.getTelegramToken()).thenReturn("token");
        Object created = method.invoke(localHandler);
        assertNotNull(created);
        Object cached = method.invoke(localHandler);
        assertNotNull(cached);
    }

    @Test
    void shouldCoverAutoMenuEmptyCollectionsBranches() {
        when(autoModeService.getGoals()).thenReturn(List.of());
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");

        Goal goalWithoutTasks = Goal.builder()
                .id("gx")
                .title("Goal X")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of())
                .build();
        when(autoModeService.getGoals()).thenReturn(List.of(goalWithoutTasks));
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasks");

        when(scheduleService.getSchedules()).thenReturn(List.of());
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schedules");
    }

    @Test
    void shouldHandleGoalDailyScheduleFailure() {
        when(scheduleService.createSchedule(
                eq(ScheduleEntry.ScheduleType.GOAL),
                eq("g1"),
                anyString(),
                anyInt())).thenThrow(new IllegalStateException("boom"));

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goalDaily:0");

        verify(scheduleService).createSchedule(eq(ScheduleEntry.ScheduleType.GOAL), eq("g1"), anyString(), anyInt());
    }

    @Test
    void shouldHandleTaskStatusParsingAndFailureBranches() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasks");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:abc:done");

        doThrow(new IllegalStateException("boom"))
                .when(autoModeService)
                .updateTaskStatus(eq("g1"), eq("t1"), eq(AutoTask.TaskStatus.COMPLETED), eq(null));
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskSet:0:done");
    }

    @Test
    void shouldHandleTaskDeleteAndDailyScheduleFailures() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:tasks");

        doThrow(new IllegalStateException("delete boom"))
                .when(autoModeService)
                .deleteTask("g1", "t1");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDelete:0");

        when(scheduleService.createSchedule(
                eq(ScheduleEntry.ScheduleType.TASK),
                eq("t1"),
                anyString(),
                anyInt())).thenThrow(new IllegalStateException("schedule boom"));
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:taskDaily:0");
    }

    @Test
    void shouldHandleScheduleDeleteFailure() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:schedules");

        doThrow(new IllegalStateException("boom"))
                .when(scheduleService)
                .deleteSchedule("s1");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:scheduleDel:0");
    }

    @Test
    void shouldCoverLocalizedGoalStatusesAndSessionTitleLoopBranches() {
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goal:1");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goal:2");
        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goal:3");

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:sessions");
        verify(telegramSessionService, atLeast(1)).listRecentSessions(CHAT_ID, 5);
    }

    @Test
    void shouldHandleEditMessageFailure() throws Exception {
        when(telegramClient.execute(any(EditMessageText.class))).thenThrow(new RuntimeException("edit failed"));

        handler.handleCallback(CHAT_ID, MESSAGE_ID, "menu:autoMenu:goals");

        verify(telegramClient, atLeast(1)).execute(any(EditMessageText.class));
    }

    private void stubPlanData() {
        me.golemcore.bot.domain.model.Plan plan = me.golemcore.bot.domain.model.Plan.builder()
                .id("plan-1")
                .title("Demo plan")
                .status(me.golemcore.bot.domain.model.Plan.PlanStatus.READY)
                .steps(List.of(
                        me.golemcore.bot.domain.model.PlanStep.builder()
                                .id("ps-1")
                                .planId("plan-1")
                                .toolName("goal_management")
                                .order(1)
                                .status(me.golemcore.bot.domain.model.PlanStep.StepStatus.PENDING)
                                .build()))
                .build();

        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(any())).thenReturn(false);
        when(planService.getPlans(any())).thenReturn(List.of(plan));
        when(planService.getPlans()).thenReturn(List.of(plan));
        when(planService.getPlan(eq("plan-1"), any())).thenReturn(Optional.of(plan));
        when(planService.getPlan("plan-1")).thenReturn(Optional.of(plan));
        when(commandRouter.execute(eq("plan"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandPort.CommandResult.success("plan ok")));
    }

    private void stubAutoData() {
        AutoTask taskPending = AutoTask.builder().id("t1").goalId("g1").title("Task pending")
                .status(AutoTask.TaskStatus.PENDING).order(1).build();
        AutoTask taskInProgress = AutoTask.builder().id("t2").goalId("g1").title("Task ip")
                .status(AutoTask.TaskStatus.IN_PROGRESS).order(2).build();
        AutoTask taskCompleted = AutoTask.builder().id("t3").goalId("g1").title("Task done")
                .status(AutoTask.TaskStatus.COMPLETED).order(3).build();
        AutoTask taskFailed = AutoTask.builder().id("t4").goalId("g1").title("Task fail")
                .status(AutoTask.TaskStatus.FAILED).order(4).build();
        AutoTask taskSkipped = AutoTask.builder().id("t5").goalId("g1").title("Task skip")
                .status(AutoTask.TaskStatus.SKIPPED).order(5).build();

        Goal goal1 = Goal.builder().id("g1").title("Goal 1").description("Main goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(taskPending, taskInProgress, taskCompleted, taskFailed, taskSkipped)))
                .build();
        Goal goal2 = Goal.builder().id("g2").title("Goal 2").status(Goal.GoalStatus.COMPLETED).tasks(List.of()).build();
        Goal goal3 = Goal.builder().id("g3").title("Goal 3").status(Goal.GoalStatus.PAUSED).tasks(List.of()).build();
        Goal goal4 = Goal.builder().id("g4").title("Goal 4").status(Goal.GoalStatus.CANCELLED).tasks(List.of()).build();
        Goal goal5 = Goal.builder().id("g5").title("Goal 5").status(Goal.GoalStatus.ACTIVE).tasks(List.of()).build();
        Goal goal6 = Goal.builder().id("g6").title("Goal 6").status(Goal.GoalStatus.ACTIVE).tasks(List.of()).build();
        Goal goal7 = Goal.builder().id("g7").title("Goal 7").status(Goal.GoalStatus.ACTIVE).tasks(List.of()).build();

        List<Goal> goals = List.of(goal1, goal2, goal3, goal4, goal5, goal6, goal7);
        Map<String, Goal> goalById = new HashMap<>();
        for (Goal goal : goals) {
            goalById.put(goal.getId(), goal);
        }

        when(autoModeService.getGoals()).thenReturn(goals);
        when(autoModeService.getGoal(anyString())).thenAnswer(invocation -> {
            String goalId = invocation.getArgument(0);
            Goal goal = goalById.get(goalId);
            return Optional.ofNullable(goal);
        });

        List<ScheduleEntry> schedules = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            ScheduleEntry.ScheduleType type = index % 2 == 0
                    ? ScheduleEntry.ScheduleType.TASK
                    : ScheduleEntry.ScheduleType.GOAL;
            schedules.add(ScheduleEntry.builder()
                    .id("s" + index)
                    .type(type)
                    .targetId(index % 2 == 0 ? "t1" : "g1")
                    .cronExpression("0 0 9 * * *")
                    .enabled(index % 3 != 0)
                    .nextExecutionAt(Instant.now())
                    .build());
        }

        when(scheduleService.getSchedules()).thenReturn(schedules);
        when(scheduleService.findSchedulesForTarget("g1")).thenReturn(List.of(schedules.get(0)));
        when(scheduleService.findSchedulesForTarget(anyString())).thenReturn(List.of());
        when(scheduleService.createSchedule(any(), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            String targetId = invocation.getArgument(1);
            String cron = invocation.getArgument(2);
            return ScheduleEntry.builder()
                    .id("created")
                    .type(ScheduleEntry.ScheduleType.GOAL)
                    .targetId(targetId)
                    .cronExpression(cron)
                    .enabled(true)
                    .build();
        });

        when(telegramSessionService.listRecentSessions(CHAT_ID, 5)).thenReturn(List.of(
                AgentSession.builder().id("telegram:conv-1").channelType("telegram").chatId("conv-1")
                        .messages(List.of(
                                me.golemcore.bot.domain.model.Message.builder().role("assistant").content("a").build(),
                                me.golemcore.bot.domain.model.Message.builder().role("user").content("u").build()))
                        .build()));
    }
}
