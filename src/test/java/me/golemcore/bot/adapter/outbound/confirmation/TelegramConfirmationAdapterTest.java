package me.golemcore.bot.adapter.outbound.confirmation;

import me.golemcore.bot.domain.model.ConfirmationCallbackEvent;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramConfirmationAdapterTest {

    private static final String CHAT_ID = "chat1";
    private static final String TOOL_NAME = "shell";
    private static final String TOOL_DESCRIPTION = "test";

    private TelegramConfirmationAdapter adapter;
    private TelegramClient telegramClient;

    @BeforeEach
    void setUp() throws Exception {
        BotProperties properties = new BotProperties();
        properties.getSecurity().getToolConfirmation().setEnabled(true);
        properties.getSecurity().getToolConfirmation().setTimeoutSeconds(5);

        adapter = new TelegramConfirmationAdapter(properties);

        telegramClient = mock(TelegramClient.class);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));
        adapter.setTelegramClient(telegramClient);
    }

    private String extractConfirmationId(int buttonIndex) throws Exception {
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        String callbackData = markup.getKeyboard().get(0).get(buttonIndex).getCallbackData();
        // Format: confirm:<id>:yes or confirm:<id>:no
        return callbackData.split(":")[1];
    }

    // ===== isAvailable =====

    @Test
    void isAvailableWhenClientSet() {
        assertTrue(adapter.isAvailable());
    }

    @Test
    void isNotAvailableWithoutClient() {
        BotProperties properties = new BotProperties();
        properties.getSecurity().getToolConfirmation().setEnabled(true);
        TelegramConfirmationAdapter noClientAdapter = new TelegramConfirmationAdapter(properties);
        assertFalse(noClientAdapter.isAvailable());
    }

    @Test
    void isNotAvailableWhenDisabled() {
        BotProperties properties = new BotProperties();
        properties.getSecurity().getToolConfirmation().setEnabled(false);
        TelegramConfirmationAdapter disabledAdapter = new TelegramConfirmationAdapter(properties);
        disabledAdapter.setTelegramClient(telegramClient);
        assertFalse(disabledAdapter.isAvailable());
    }

    // ===== Lazy TelegramClient initialization =====

    @Test
    void shouldNotBeAvailableWhenTelegramChannelDisabled() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        BotProperties.ChannelProperties channelProps = new BotProperties.ChannelProperties();
        channelProps.setEnabled(false);
        props.getChannels().put("telegram", channelProps);

        TelegramConfirmationAdapter lazyAdapter = new TelegramConfirmationAdapter(props);
        assertFalse(lazyAdapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenTokenBlank() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        BotProperties.ChannelProperties channelProps = new BotProperties.ChannelProperties();
        channelProps.setEnabled(true);
        channelProps.setToken("   ");
        props.getChannels().put("telegram", channelProps);

        TelegramConfirmationAdapter lazyAdapter = new TelegramConfirmationAdapter(props);
        assertFalse(lazyAdapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenTokenNull() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        BotProperties.ChannelProperties channelProps = new BotProperties.ChannelProperties();
        channelProps.setEnabled(true);
        channelProps.setToken(null);
        props.getChannels().put("telegram", channelProps);

        TelegramConfirmationAdapter lazyAdapter = new TelegramConfirmationAdapter(props);
        assertFalse(lazyAdapter.isAvailable());
    }

    @Test
    void shouldNotBeAvailableWhenNoTelegramChannel() {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        // No "telegram" key in channels map

        TelegramConfirmationAdapter lazyAdapter = new TelegramConfirmationAdapter(props);
        assertFalse(lazyAdapter.isAvailable());
    }

    @Test
    void shouldAutoApproveWhenLazyInitFails() throws Exception {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        // No telegram channel configured → getOrCreateClient returns null

        TelegramConfirmationAdapter lazyAdapter = new TelegramConfirmationAdapter(props);
        CompletableFuture<Boolean> result = lazyAdapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    // ===== Core confirmation flow =====

    @Test
    void autoApprovesWhenNotAvailable() throws Exception {
        BotProperties properties = new BotProperties();
        properties.getSecurity().getToolConfirmation().setEnabled(true);
        TelegramConfirmationAdapter noClientAdapter = new TelegramConfirmationAdapter(properties);

        CompletableFuture<Boolean> result = noClientAdapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void confirmationApproved() throws Exception {
        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, "echo hello");
        String confirmationId = extractConfirmationId(0);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, CHAT_ID, "42"));
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void confirmationDenied() throws Exception {
        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, "rm -rf test");
        String confirmationId = extractConfirmationId(1);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, false, CHAT_ID, "42"));
        assertFalse(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void confirmationTimeout() throws Exception {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        props.getSecurity().getToolConfirmation().setTimeoutSeconds(1);

        TelegramConfirmationAdapter shortTimeoutAdapter = new TelegramConfirmationAdapter(props);
        shortTimeoutAdapter.setTelegramClient(telegramClient);

        CompletableFuture<Boolean> result = shortTimeoutAdapter.requestConfirmation(CHAT_ID, TOOL_NAME,
                TOOL_DESCRIPTION);

        Boolean approved = result.get(3, TimeUnit.SECONDS);
        assertFalse(approved);
    }

    // ===== Event listener =====

    @Test
    void unknownConfirmationIdIsIgnored() {
        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent("unknown-id", true, CHAT_ID, "42"));
        // Should not throw, just log debug
    }

    @Test
    void callbackUpdatesMessageOnApproval() throws Exception {
        when(telegramClient.execute(any(EditMessageText.class))).thenReturn(null);

        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        String confirmationId = extractConfirmationId(0);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, CHAT_ID, "99"));
        result.get(1, TimeUnit.SECONDS);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        EditMessageText edit = editCaptor.getValue();
        assertEquals(CHAT_ID, edit.getChatId());
        assertEquals(99, edit.getMessageId());
        assertTrue(edit.getText().contains("Confirmed"));
    }

    @Test
    void callbackUpdatesMessageOnDenial() throws Exception {
        when(telegramClient.execute(any(EditMessageText.class))).thenReturn(null);

        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        String confirmationId = extractConfirmationId(0);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, false, CHAT_ID, "50"));
        result.get(1, TimeUnit.SECONDS);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        assertTrue(editCaptor.getValue().getText().contains("Cancelled"));
    }

    @Test
    void shouldNotCrashWhenMessageUpdateFails() throws Exception {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenThrow(new RuntimeException("Telegram API error"));

        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        String confirmationId = extractConfirmationId(0);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, CHAT_ID, "99"));

        // Future should still resolve despite EditMessage failure
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldNotCrashWhenMessageIdNotNumeric() throws Exception {
        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        String confirmationId = extractConfirmationId(0);

        // "not-a-number" will cause NumberFormatException in Integer.parseInt
        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, CHAT_ID, "not-a-number"));

        // Future should still resolve — the exception is caught in
        // updateConfirmationMessage
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    // ===== Duplicate/concurrent callbacks =====

    @Test
    void shouldIgnoreDuplicateCallback() throws Exception {
        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        String confirmationId = extractConfirmationId(0);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, CHAT_ID, "42"));
        assertTrue(result.get(1, TimeUnit.SECONDS));

        // Second callback with same ID — should be ignored silently
        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, false, CHAT_ID, "42"));
        // No exception, result stays true
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldTrackMultiplePendingConfirmations() throws Exception {
        CompletableFuture<Boolean> result1 = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, "cmd1");
        ArgumentCaptor<SendMessage> captor1 = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor1.capture());
        String id1 = extractIdFromSendMessage(captor1.getValue(), 0);

        reset(telegramClient);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Boolean> result2 = adapter.requestConfirmation("chat2", "browser", "cmd2");
        ArgumentCaptor<SendMessage> captor2 = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor2.capture());
        String id2 = extractIdFromSendMessage(captor2.getValue(), 0);

        assertNotEquals(id1, id2);

        // Resolve in reverse order
        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(id2, false, "chat2", "2"));
        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(id1, true, CHAT_ID, "1"));

        assertTrue(result1.get(1, TimeUnit.SECONDS));
        assertFalse(result2.get(1, TimeUnit.SECONDS));
    }

    // ===== HTML escaping =====

    @Test
    void shouldEscapeHtmlInToolNameAndDescription() throws Exception {
        adapter.requestConfirmation(CHAT_ID, "<script>alert(1)</script>", "a & b > c");

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        String text = captor.getValue().getText();

        assertTrue(text.contains("&lt;script&gt;"));
        assertTrue(text.contains("a &amp; b &gt; c"));
        assertFalse(text.contains("<script>"));
    }

    @Test
    void shouldAutoApproveWhenSendFails() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(new RuntimeException("Send failed"));

        CompletableFuture<Boolean> result = adapter.requestConfirmation(CHAT_ID, TOOL_NAME, TOOL_DESCRIPTION);
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    // ===== Helpers =====

    private String extractIdFromSendMessage(SendMessage sent, int buttonIndex) {
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        String callbackData = markup.getKeyboard().get(0).get(buttonIndex).getCallbackData();
        return callbackData.split(":")[1];
    }
}
