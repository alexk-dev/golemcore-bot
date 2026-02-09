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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAdapterHandleMessageTest {

    private static final String COMMAND_HELP = "help";

    private TelegramAdapter adapter;
    private TelegramClient telegramClient;
    private AllowlistValidator allowlistValidator;
    private CommandPort commandRouter;
    private UserPreferencesService preferencesService;
    private MessageService messageService;
    private ApplicationEventPublisher eventPublisher;
    private Consumer<Message> messageHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        telegramProps.setToken("test-token");
        when(properties.getChannels()).thenReturn(Map.of("telegram", telegramProps));

        allowlistValidator = mock(AllowlistValidator.class);
        when(allowlistValidator.isAllowed("telegram", "123")).thenReturn(true);
        when(allowlistValidator.isBlocked("123")).thenReturn(false);

        eventPublisher = mock(ApplicationEventPublisher.class);
        telegramClient = mock(TelegramClient.class);
        commandRouter = mock(CommandPort.class);
        preferencesService = mock(UserPreferencesService.class);
        messageService = mock(MessageService.class);

        adapter = new TelegramAdapter(
                properties,
                allowlistValidator,
                eventPublisher,
                mock(TelegramBotsLongPollingApplication.class),
                preferencesService,
                messageService,
                commandRouter,
                mock(TelegramVoiceHandler.class));
        adapter.setTelegramClient(telegramClient);

        messageHandler = mock(Consumer.class);
        adapter.onMessage(messageHandler);
    }

    // ===== Command routing =====

    @Test
    void shouldRouteKnownCommandToRouter() throws Exception {
        when(commandRouter.hasCommand(COMMAND_HELP)).thenReturn(true);

        CommandPort.CommandResult result = CommandPort.CommandResult.success("Help text");
        when(commandRouter.execute(eq(COMMAND_HELP), eq(List.of()), any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        Update update = createTextUpdate(123L, 100L, "/help");
        adapter.consume(update);

        verify(commandRouter).hasCommand(COMMAND_HELP);
        verify(commandRouter).execute(eq(COMMAND_HELP), eq(List.of()), any());
        verify(messageHandler, never()).accept(any());
    }

    @Test
    void shouldRouteCommandWithArgs() throws Exception {
        when(commandRouter.hasCommand("compact")).thenReturn(true);

        CommandPort.CommandResult result = CommandPort.CommandResult.success("Compacted");
        when(commandRouter.execute(eq("compact"), eq(List.of("10")), any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        Update update = createTextUpdate(123L, 100L, "/compact 10");
        adapter.consume(update);

        verify(commandRouter).execute(eq("compact"), eq(List.of("10")), any());
        verify(messageHandler, never()).accept(any());
    }

    @Test
    void shouldHandleSettingsCommand() throws Exception {
        when(preferencesService.getMessage("settings.title")).thenReturn("Settings");
        when(preferencesService.getMessage(anyString())).thenReturn("Settings");
        when(preferencesService.getMessage(eq("settings.language.current"), anyString())).thenReturn("Language: EN");
        when(preferencesService.getMessage("settings.language.select")).thenReturn("Select language:");
        when(preferencesService.getLanguage()).thenReturn("en");
        when(messageService.getLanguageDisplayName("en")).thenReturn("English");
        when(preferencesService.getMessage("button.language.en")).thenReturn("English");
        when(preferencesService.getMessage("button.language.ru")).thenReturn("Russian");

        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        Update update = createTextUpdate(123L, 100L, "/settings");
        adapter.consume(update);

        // Settings should be handled directly, not routed to commandRouter
        verify(commandRouter, never()).hasCommand("settings");
        verify(telegramClient, timeout(2000)).execute(any(SendMessage.class));
        verify(messageHandler, never()).accept(any());
    }

    @Test
    void shouldHandleCommandWithBotMention() throws Exception {
        when(commandRouter.hasCommand(COMMAND_HELP)).thenReturn(true);

        CommandPort.CommandResult result = CommandPort.CommandResult.success("Help text");
        when(commandRouter.execute(eq(COMMAND_HELP), eq(List.of()), any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        // Command with @botname suffix
        Update update = createTextUpdate(123L, 100L, "/help@mybot");
        adapter.consume(update);

        verify(commandRouter).hasCommand(COMMAND_HELP);
    }

    @Test
    void shouldHandleCommandFailure() throws Exception {
        when(commandRouter.hasCommand("failing")).thenReturn(true);
        when(commandRouter.execute(eq("failing"), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Command error")));

        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        Update update = createTextUpdate(123L, 100L, "/failing");
        adapter.consume(update);

        // Should send error message
        verify(telegramClient, timeout(2000)).execute(any(SendMessage.class));
        verify(messageHandler, never()).accept(any());
    }

    // ===== Unknown command passthrough =====

    @Test
    void shouldPassUnknownCommandAsRegularMessage() {
        when(commandRouter.hasCommand("unknown")).thenReturn(false);

        Update update = createTextUpdate(123L, 100L, "/unknown");
        adapter.consume(update);

        // Unknown command should be passed to message handler as text
        verify(messageHandler).accept(any(Message.class));
    }

    // ===== Regular text message =====

    @Test
    void shouldPassRegularTextToHandler() {
        Update update = createTextUpdate(123L, 100L, "Hello world");
        adapter.consume(update);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageHandler).accept(captor.capture());

        Message msg = captor.getValue();
        assertEquals("Hello world", msg.getContent());
        assertEquals("100", msg.getChatId());
        assertEquals("123", msg.getSenderId());
        assertEquals("user", msg.getRole());
        assertEquals("telegram", msg.getChannelType());
    }

    // ===== Event publishing =====

    @Test
    void shouldPublishInboundMessageEvent() {
        Update update = createTextUpdate(123L, 100L, "Hello");
        adapter.consume(update);

        verify(eventPublisher).publishEvent(any(Object.class));
    }

    // ===== Update without message =====

    @Test
    void shouldIgnoreUpdateWithoutMessageAndWithoutCallback() {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);
        when(update.hasCallbackQuery()).thenReturn(false);

        adapter.consume(update);

        verify(messageHandler, never()).accept(any());
    }

    // ===== Message without text and without voice =====

    @Test
    void shouldHandleMessageWithoutTextOrVoice() {
        User user = createUser(123L);
        org.telegram.telegrambots.meta.api.objects.message.Message telegramMsg = mock(
                org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(telegramMsg.getChatId()).thenReturn(100L);
        when(telegramMsg.getFrom()).thenReturn(user);
        when(telegramMsg.getMessageId()).thenReturn(1);
        when(telegramMsg.hasText()).thenReturn(false);
        when(telegramMsg.hasVoice()).thenReturn(false);

        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.getMessage()).thenReturn(telegramMsg);

        adapter.consume(update);

        // Should still pass message to handler (possibly with null content)
        verify(messageHandler).accept(any(Message.class));
    }

    // ===== Helpers =====

    private Update createTextUpdate(long userId, long chatId, String text) {
        User user = createUser(userId);
        org.telegram.telegrambots.meta.api.objects.message.Message telegramMsg = mock(
                org.telegram.telegrambots.meta.api.objects.message.Message.class);
        when(telegramMsg.getChatId()).thenReturn(chatId);
        when(telegramMsg.getFrom()).thenReturn(user);
        when(telegramMsg.getMessageId()).thenReturn(1);
        when(telegramMsg.hasText()).thenReturn(true);
        when(telegramMsg.getText()).thenReturn(text);
        when(telegramMsg.hasVoice()).thenReturn(false);

        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.getMessage()).thenReturn(telegramMsg);

        return update;
    }

    private User createUser(long userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        return user;
    }
}
