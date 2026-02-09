package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.ConfirmationCallbackEvent;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramAdapterCallbackTest {

    private TelegramAdapter adapter;
    private ApplicationEventPublisher eventPublisher;
    private TelegramClient telegramClient;

    @BeforeEach
    void setUp() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        when(properties.getChannels()).thenReturn(java.util.Map.of("telegram", telegramProps));

        eventPublisher = mock(ApplicationEventPublisher.class);
        telegramClient = mock(TelegramClient.class);

        adapter = new TelegramAdapter(
                properties,
                mock(AllowlistValidator.class),
                eventPublisher,
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                mock(MessageService.class),
                mock(CommandPort.class),
                mock(TelegramVoiceHandler.class));
        adapter.setTelegramClient(telegramClient);
    }

    @Test
    void shouldPublishConfirmationEventOnApproval() {
        Update update = createCallbackUpdate("100", 42, "confirm:abc123:yes");

        adapter.consume(update);

        ArgumentCaptor<ConfirmationCallbackEvent> captor = ArgumentCaptor.forClass(ConfirmationCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ConfirmationCallbackEvent event = captor.getValue();

        assertEquals("abc123", event.confirmationId());
        assertTrue(event.approved());
        assertEquals("100", event.chatId());
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
        Update update = createCallbackUpdate("100", 1, "confirm:abc");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(ConfirmationCallbackEvent.class));
    }

    @Test
    void shouldIgnoreInvalidCallbackFormat_tooManyParts() {
        Update update = createCallbackUpdate("100", 1, "confirm:a:b:c");

        adapter.consume(update);

        verify(eventPublisher, never()).publishEvent(any(ConfirmationCallbackEvent.class));
    }

    @Test
    void shouldNotPublishForNonConfirmCallback() {
        Update update = createCallbackUpdate("100", 1, "lang:en");

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
        Update update = createCallbackUpdate("100", 42, "confirm:abc123:maybe");

        adapter.consume(update);

        ArgumentCaptor<ConfirmationCallbackEvent> captor = ArgumentCaptor.forClass(ConfirmationCallbackEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertFalse(captor.getValue().approved());
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
