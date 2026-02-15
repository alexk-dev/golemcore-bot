package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.Message;
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
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAdapterAuthTest {

    private static final String CHANNEL_TELEGRAM = "telegram";

    private TelegramAdapter adapter;
    private AllowlistValidator allowlistValidator;
    private MessageService messageService;
    private TelegramClient telegramClient;
    private Consumer<Message> messageHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        when(properties.getChannels()).thenReturn(java.util.Map.of(CHANNEL_TELEGRAM, telegramProps));

        allowlistValidator = mock(AllowlistValidator.class);
        messageService = mock(MessageService.class);
        telegramClient = mock(TelegramClient.class);

        adapter = new TelegramAdapter(
                properties,
                allowlistValidator,
                mock(ApplicationEventPublisher.class),
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                messageService,
                new TestObjectProvider<>(mock(CommandPort.class)),
                mock(TelegramVoiceHandler.class),
                mock(TelegramMenuHandler.class));
        adapter.setTelegramClient(telegramClient);

        messageHandler = mock(Consumer.class);
        adapter.onMessage(messageHandler);
    }

    @Test
    void unauthorizedUser_sendsAccessDeniedAndDoesNotProcess() throws Exception {
        when(allowlistValidator.isAllowed(CHANNEL_TELEGRAM, "999")).thenReturn(false);
        when(messageService.getMessage("security.unauthorized")).thenReturn("Access denied.");

        Update update = createTextUpdate(999L, 100L, "Hello bot");

        adapter.consume(update);

        // Verify unauthorized message was sent (async — use timeout)
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, timeout(2000)).execute(captor.capture());
        assertEquals("100", captor.getValue().getChatId());
        assertTrue(captor.getValue().getText().contains("Access denied"));

        // Verify message was NOT passed to handler (no LLM processing)
        verify(messageHandler, never()).accept(any());
    }

    @Test
    void authorizedUser_processesMessageNormally() {
        when(allowlistValidator.isAllowed(CHANNEL_TELEGRAM, "123")).thenReturn(true);
        when(allowlistValidator.isBlocked("123")).thenReturn(false);

        Update update = createTextUpdate(123L, 100L, "Hello bot");

        adapter.consume(update);

        // Verify message WAS passed to handler
        verify(messageHandler).accept(any(Message.class));
    }

    @Test
    void blockedUser_sendsAccessDeniedAndDoesNotProcess() throws Exception {
        when(allowlistValidator.isAllowed(CHANNEL_TELEGRAM, "456")).thenReturn(true);
        when(allowlistValidator.isBlocked("456")).thenReturn(true);
        when(messageService.getMessage("security.unauthorized")).thenReturn("Access denied.");

        Update update = createTextUpdate(456L, 200L, "Hello");

        adapter.consume(update);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, timeout(2000)).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Access denied"));

        verify(messageHandler, never()).accept(any());
    }

    private Update createTextUpdate(long userId, long chatId, String text) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);

        org.telegram.telegrambots.meta.api.objects.chat.Chat chat = mock(
                org.telegram.telegrambots.meta.api.objects.chat.Chat.class);
        when(chat.getId()).thenReturn(chatId);

        org.telegram.telegrambots.meta.api.objects.message.Message telegramMsg = mock(
                org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(telegramMsg.getChatId()).thenReturn(chatId);
        when(telegramMsg.getFrom()).thenReturn(user);
        when(telegramMsg.getMessageId()).thenReturn(1);
        when(telegramMsg.hasText()).thenReturn(true);
        when(telegramMsg.getText()).thenReturn(text);

        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.getMessage()).thenReturn(telegramMsg);

        return update;
    }
}
