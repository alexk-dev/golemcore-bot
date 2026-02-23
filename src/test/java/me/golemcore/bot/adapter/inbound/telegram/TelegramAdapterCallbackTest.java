package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.ConfirmationCallbackEvent;
import me.golemcore.bot.domain.model.PlanApprovalCallbackEvent;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TelegramSessionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.security.AllowlistValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAdapterCallbackTest {

    private static final String CHAT_ID_100 = "100";

    private TelegramAdapter adapter;
    private ApplicationEventPublisher eventPublisher;
    private TelegramClient telegramClient;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        telegramClient = mock(TelegramClient.class);

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.getTelegramToken()).thenReturn("test-token");

        adapter = new TelegramAdapter(
                runtimeConfigService,
                mock(AllowlistValidator.class),
                eventPublisher,
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                mock(MessageService.class),
                new TestObjectProvider<>(mock(CommandPort.class)),
                mock(TelegramVoiceHandler.class),
                mock(TelegramMenuHandler.class),
                mock(TelegramSessionService.class));
        adapter.setTelegramClient(telegramClient);
    }

    @Test
    void shouldPublishConfirmationEventOnApproval() {
        Update update = createCallbackUpdate(CHAT_ID_100, 42, "confirm:abc123:yes");

        adapter.consume(update);

        ArgumentCaptor<ConfirmationCallbackEvent> captor = ArgumentCaptor.forClass(ConfirmationCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ConfirmationCallbackEvent event = captor.getValue();

        assertEquals("abc123", event.confirmationId());
        assertTrue(event.approved());
        assertEquals(CHAT_ID_100, event.chatId());
        assertEquals("42", event.messageId());
    }

    @Test
    void shouldPublishConfirmationEventOnDenial() {
        Update update = createCallbackUpdate("200", 99, "confirm:def456:no");

        adapter.consume(update);

        ArgumentCaptor<ConfirmationCallbackEvent> captor = ArgumentCaptor.forClass(ConfirmationCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ConfirmationCallbackEvent event = captor.getValue();

        assertEquals("def456", event.confirmationId());
        assertFalse(event.approved());
        assertEquals("200", event.chatId());
        assertEquals("99", event.messageId());
    }

    @Test
    void shouldIgnoreInvalidCallbackFormat_tooParts() {
        Update update = createCallbackUpdate(CHAT_ID_100, 1, "confirm:abc");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(ConfirmationCallbackEvent.class));
    }

    @Test
    void shouldIgnoreInvalidCallbackFormat_tooManyParts() {
        Update update = createCallbackUpdate(CHAT_ID_100, 1, "confirm:a:b:c");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(ConfirmationCallbackEvent.class));
    }

    @Test
    void shouldNotPublishForNonConfirmCallback() {
        Update update = createCallbackUpdate(CHAT_ID_100, 1, "lang:en");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(ConfirmationCallbackEvent.class));
    }

    @Test
    void shouldIgnoreCallbackWithoutMessage() {
        CallbackQuery callback = mock(CallbackQuery.class);
        when(callback.getMessage()).thenReturn(null);

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.hasMessage()).thenReturn(false);
        when(update.getCallbackQuery()).thenReturn(callback);

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(ConfirmationCallbackEvent.class));
    }

    @Test
    void shouldTreatUnknownResponseAsNotApproved() {
        Update update = createCallbackUpdate(CHAT_ID_100, 42, "confirm:abc123:maybe");

        adapter.consume(update);

        ArgumentCaptor<ConfirmationCallbackEvent> captor = ArgumentCaptor.forClass(ConfirmationCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertFalse(captor.getValue().approved());
    }

    // ===== Plan callbacks =====

    @Test
    void shouldPublishPlanApprovalEvent() {
        Update update = createCallbackUpdate(CHAT_ID_100, 42, "plan:plan-abc:approve");

        adapter.consume(update);

        ArgumentCaptor<PlanApprovalCallbackEvent> captor = ArgumentCaptor.forClass(PlanApprovalCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlanApprovalCallbackEvent event = captor.getValue();

        assertEquals("plan-abc", event.planId());
        assertEquals("approve", event.action());
        assertEquals(CHAT_ID_100, event.chatId());
        assertEquals("42", event.messageId());
    }

    @Test
    void shouldPublishPlanCancelEvent() {
        Update update = createCallbackUpdate("200", 99, "plan:plan-xyz:cancel");

        adapter.consume(update);

        ArgumentCaptor<PlanApprovalCallbackEvent> captor = ArgumentCaptor.forClass(PlanApprovalCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PlanApprovalCallbackEvent event = captor.getValue();

        assertEquals("plan-xyz", event.planId());
        assertEquals("cancel", event.action());
    }

    @Test
    void shouldIgnoreInvalidPlanCallbackFormat() {
        Update update = createCallbackUpdate(CHAT_ID_100, 1, "plan:abc");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(PlanApprovalCallbackEvent.class));
    }

    @Test
    void shouldIgnorePlanCallbackWithTooManyParts() {
        Update update = createCallbackUpdate(CHAT_ID_100, 1, "plan:a:b:c");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(PlanApprovalCallbackEvent.class));
    }

    private Update createCallbackUpdate(String chatId, int messageId, String data) {
        org.telegram.telegrambots.meta.api.objects.message.Message message = mock(
                org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(message.getChatId()).thenReturn(Long.parseLong(chatId));
        when(message.getMessageId()).thenReturn(messageId);

        CallbackQuery callback = mock(CallbackQuery.class);
        when(callback.getMessage()).thenReturn(message);
        when(callback.getData()).thenReturn(data);

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.hasMessage()).thenReturn(false);
        when(update.getCallbackQuery()).thenReturn(callback);

        return update;
    }
}
