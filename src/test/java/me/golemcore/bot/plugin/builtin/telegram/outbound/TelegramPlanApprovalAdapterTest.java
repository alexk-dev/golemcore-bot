package me.golemcore.bot.plugin.builtin.telegram.outbound;

import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanApprovalCallbackEvent;
import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramPlanApprovalAdapterTest {

    private static final String PLAN_ID = "plan-001";
    private static final String CHAT_ID = "chat-123";
    private static final String MESSAGE_ID = "42";
    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_CANCEL = "cancel";
    private static final String TOOL_FILESYSTEM = "filesystem";
    private static final String SUMMARY = "Summary";
    private static final String TITLE_TEST = "Test";
    private static final Instant NOW = Instant.parse("2026-02-11T10:00:00Z");

    private PlanService planService;
    private PlanExecutionService planExecutionService;
    private BotProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private TelegramClient telegramClient;
    private TelegramPlanApprovalAdapter adapter;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        planExecutionService = mock(PlanExecutionService.class);
        properties = new BotProperties();
        properties.getPlan().setEnabled(true);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.getTelegramToken()).thenReturn("test-token");

        adapter = new TelegramPlanApprovalAdapter(planService, planExecutionService, properties, runtimeConfigService);

        telegramClient = mock(TelegramClient.class);
        adapter.setTelegramClient(telegramClient);
    }

    // ==================== onPlanReady ====================

    @Test
    void shouldSkipOnPlanReadyWhenFeatureDisabled() {
        when(planService.isFeatureEnabled()).thenReturn(false);

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        verify(planService, never()).getPlan(any());
    }

    @Test
    void shouldSendApprovalUiOnPlanReady() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps("Test Plan");
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();
        assertEquals(CHAT_ID, sent.getChatId());
        assertTrue(sent.getText().contains("Plan Ready for Approval"));
        assertTrue(sent.getText().contains(TOOL_FILESYSTEM));
        assertEquals("HTML", sent.getParseMode());

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        String approveCallback = markup.getKeyboard().get(0).get(0).getCallbackData();
        String cancelCallback = markup.getKeyboard().get(0).get(1).getCallbackData();
        assertEquals("plan:" + PLAN_ID + ":approve", approveCallback);
        assertEquals("plan:" + PLAN_ID + ":cancel", cancelCallback);
    }

    @Test
    void shouldHandlePlanNotFoundOnReady() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.empty());

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));
        // Should not throw, plan not found means no approval UI sent
    }

    @Test
    void shouldHandleNullClientOnPlanReady() {
        RuntimeConfigService disabledRuntimeConfig = mock(RuntimeConfigService.class);
        when(disabledRuntimeConfig.isTelegramEnabled()).thenReturn(false);
        TelegramPlanApprovalAdapter noClientAdapter = new TelegramPlanApprovalAdapter(
                planService, planExecutionService, properties, disabledRuntimeConfig);
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps("Test Plan");
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        noClientAdapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));
        // Should not throw — no client available
    }

    @Test
    void shouldHandleSendExceptionOnPlanReady() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps("Test Plan");
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new RuntimeException("Telegram API error"));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));
        // Should not throw — exception caught internally
    }

    // ==================== onPlanApproval — approve ====================

    @Test
    void shouldSkipOnPlanApprovalWhenFeatureDisabled() {
        when(planService.isFeatureEnabled()).thenReturn(false);

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, ACTION_APPROVE, CHAT_ID, MESSAGE_ID));

        verify(planService, never()).approvePlan(any());
    }

    @Test
    void shouldAprovePlanAndStartExecution() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(telegramClient.execute(any(EditMessageText.class))).thenReturn(null);

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, ACTION_APPROVE, CHAT_ID, MESSAGE_ID));

        verify(planService).approvePlan(PLAN_ID);
        verify(planExecutionService).executePlan(PLAN_ID);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        EditMessageText edit = captor.getValue();
        assertEquals(CHAT_ID, edit.getChatId());
        assertEquals(42, edit.getMessageId());
        assertTrue(edit.getText().contains("approved"));
    }

    @Test
    void shouldHandleApproveException() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("Bad state")).when(planService).approvePlan(PLAN_ID);
        when(telegramClient.execute(any(EditMessageText.class))).thenReturn(null);

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, ACTION_APPROVE, CHAT_ID, MESSAGE_ID));

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Failed"));
    }

    // ==================== onPlanApproval — cancel ====================

    @Test
    void shouldCancelPlanOnCancelAction() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(telegramClient.execute(any(EditMessageText.class))).thenReturn(null);

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, ACTION_CANCEL, CHAT_ID, MESSAGE_ID));

        verify(planService).cancelPlan(PLAN_ID);

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("cancelled"));
    }

    @Test
    void shouldHandleCancelException() {
        when(planService.isFeatureEnabled()).thenReturn(true);
        doThrow(new IllegalArgumentException("Not found")).when(planService).cancelPlan(PLAN_ID);

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, ACTION_CANCEL, CHAT_ID, MESSAGE_ID));
        // Should not throw — exception caught internally
    }

    @Test
    void shouldIgnoreUnknownAction() {
        when(planService.isFeatureEnabled()).thenReturn(true);

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, "unknown", CHAT_ID, MESSAGE_ID));

        verify(planService, never()).approvePlan(any());
        verify(planService, never()).cancelPlan(any());
    }

    // ==================== onPlanExecutionCompleted ====================

    @Test
    void shouldSendExecutionSummary() throws Exception {
        adapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY + " text"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();
        assertEquals(CHAT_ID, sent.getChatId());
        assertEquals(SUMMARY + " text", sent.getText());
        assertEquals("Markdown", sent.getParseMode());
    }

    @Test
    void shouldHandleNullClientOnExecutionCompleted() {
        RuntimeConfigService disabledRuntimeConfig = mock(RuntimeConfigService.class);
        when(disabledRuntimeConfig.isTelegramEnabled()).thenReturn(false);
        TelegramPlanApprovalAdapter noClientAdapter = new TelegramPlanApprovalAdapter(
                planService, planExecutionService, properties, disabledRuntimeConfig);

        noClientAdapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY));
        // Should not throw — no client available
    }

    @Test
    void shouldHandleSendExceptionOnExecutionCompleted() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new RuntimeException("API error"));

        adapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY));
        // Should not throw — exception caught internally
    }

    // ==================== buildApprovalMessage ====================

    @Test
    void shouldBuildApprovalMessageWithTitle() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps("Refactor Auth");
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains("Refactor Auth"));
    }

    @Test
    void shouldBuildApprovalMessageWithoutTitle() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps(null);
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains("Plan Ready for Approval"));
        assertTrue(text.contains(TOOL_FILESYSTEM));
    }

    @Test
    void shouldBuildApprovalMessageWithModelTier() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps(TITLE_TEST);
        plan.setModelTier("smart");
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Tier: smart"));
    }

    @Test
    void shouldBuildApprovalMessageWithoutModelTier() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps(TITLE_TEST);
        plan.setModelTier(null);
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertFalse(captor.getValue().getText().contains("Tier:"));
    }

    @Test
    void shouldBuildApprovalMessageWithStepDescription() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .title(TITLE_TEST)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM)
                                .description("Read config file").order(0).build())))
                .createdAt(NOW).updatedAt(NOW).build();
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Read config file"));
    }

    @Test
    void shouldBuildApprovalMessageWithBlankStepDescription() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .title(TITLE_TEST)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM)
                                .description("   ").order(0).build())))
                .createdAt(NOW).updatedAt(NOW).build();
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains(TOOL_FILESYSTEM));
    }

    @Test
    void shouldBuildApprovalMessageWithNullStepDescription() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .title(TITLE_TEST)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM)
                                .description(null).order(0).build())))
                .createdAt(NOW).updatedAt(NOW).build();
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains(TOOL_FILESYSTEM));
    }

    @Test
    void shouldEscapeHtmlInApprovalMessage() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .title("<b>Dangerous & Evil</b>")
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName("shell")
                                .description("run <script> & test").order(0).build())))
                .createdAt(NOW).updatedAt(NOW).build();
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains("&lt;b&gt;Dangerous &amp; Evil&lt;/b&gt;"));
        assertTrue(text.contains("run &lt;script&gt; &amp; test"));
    }

    @Test
    void shouldTruncateLongStepDescription() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        String longDesc = "A".repeat(100);
        Plan plan = Plan.builder()
                .id(PLAN_ID)
                .title(TITLE_TEST)
                .steps(new ArrayList<>(List.of(
                        PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM)
                                .description(longDesc).order(0).build())))
                .createdAt(NOW).updatedAt(NOW).build();
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains("..."));
    }

    // ==================== updateMessage ====================

    @Test
    void shouldHandleNullClientOnUpdateMessage() {
        RuntimeConfigService disabledRuntimeConfig = mock(RuntimeConfigService.class);
        when(disabledRuntimeConfig.isTelegramEnabled()).thenReturn(false);
        TelegramPlanApprovalAdapter noClientAdapter = new TelegramPlanApprovalAdapter(
                planService, planExecutionService, properties, disabledRuntimeConfig);
        when(planService.isFeatureEnabled()).thenReturn(true);

        noClientAdapter.onPlanApproval(
                new PlanApprovalCallbackEvent(PLAN_ID, ACTION_CANCEL, CHAT_ID, MESSAGE_ID));
        // cancelPlan is called but updateMessage silently returns on null client
        verify(planService).cancelPlan(PLAN_ID);
    }

    @Test
    void shouldHandleEditExceptionOnUpdateMessage() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new RuntimeException("Edit failed"));

        adapter.onPlanApproval(new PlanApprovalCallbackEvent(PLAN_ID, ACTION_CANCEL, CHAT_ID, MESSAGE_ID));
        // Should not throw — exception caught
        verify(planService).cancelPlan(PLAN_ID);
    }

    // ==================== getOrCreateClient (lazy init) ====================

    @Test
    void shouldReturnCachedClient() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        Plan plan = buildPlanWithSteps(TITLE_TEST);
        when(planService.getPlan(PLAN_ID)).thenReturn(Optional.of(plan));

        // First call — uses pre-set client
        adapter.onPlanReady(new PlanReadyEvent(PLAN_ID, CHAT_ID));
        // Second call — should reuse cached client
        adapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY));

        // Both calls should use the same (cached) client — verify 2 SendMessage calls
        verify(telegramClient, org.mockito.Mockito.times(2)).execute(any(SendMessage.class));
    }

    @Test
    void shouldReturnNullWhenTelegramDisabled() {
        RuntimeConfigService disabledRuntimeConfig = mock(RuntimeConfigService.class);
        when(disabledRuntimeConfig.isTelegramEnabled()).thenReturn(false);
        TelegramPlanApprovalAdapter disabledAdapter = new TelegramPlanApprovalAdapter(
                planService, planExecutionService, properties, disabledRuntimeConfig);

        disabledAdapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY));
        // Should not throw
    }

    @Test
    void shouldReturnNullWhenTokenIsNull() {
        RuntimeConfigService nullTokenConfig = mock(RuntimeConfigService.class);
        when(nullTokenConfig.isTelegramEnabled()).thenReturn(true);
        when(nullTokenConfig.getTelegramToken()).thenReturn(null);

        TelegramPlanApprovalAdapter nullTokenAdapter = new TelegramPlanApprovalAdapter(
                planService, planExecutionService, properties, nullTokenConfig);

        nullTokenAdapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY));
        // Should not throw
    }

    @Test
    void shouldReturnNullWhenTokenIsBlank() {
        RuntimeConfigService blankTokenConfig = mock(RuntimeConfigService.class);
        when(blankTokenConfig.isTelegramEnabled()).thenReturn(true);
        when(blankTokenConfig.getTelegramToken()).thenReturn("   ");

        TelegramPlanApprovalAdapter blankTokenAdapter = new TelegramPlanApprovalAdapter(
                planService, planExecutionService, properties, blankTokenConfig);

        blankTokenAdapter.onPlanExecutionCompleted(
                new PlanExecutionCompletedEvent(PLAN_ID, CHAT_ID, SUMMARY));
        // Should not throw
    }

    // ==================== Helpers ====================

    private Plan buildPlanWithSteps(String title) {
        List<PlanStep> steps = new ArrayList<>(List.of(
                PlanStep.builder().id("s1").toolName(TOOL_FILESYSTEM)
                        .description("write file").order(0).build(),
                PlanStep.builder().id("s2").toolName("shell")
                        .description("run tests").order(1).build()));
        return Plan.builder()
                .id(PLAN_ID)
                .title(title)
                .status(Plan.PlanStatus.READY)
                .steps(steps)
                .chatId(CHAT_ID)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
