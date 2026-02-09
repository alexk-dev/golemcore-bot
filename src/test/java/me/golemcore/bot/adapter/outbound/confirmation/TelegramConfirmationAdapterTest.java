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

    @Test
    void autoApprovesWhenNotAvailable() throws Exception {
        BotProperties properties = new BotProperties();
        properties.getSecurity().getToolConfirmation().setEnabled(true);
        TelegramConfirmationAdapter noClientAdapter = new TelegramConfirmationAdapter(properties);

        CompletableFuture<Boolean> result = noClientAdapter.requestConfirmation("chat1", "shell", "test");
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void confirmationApproved() throws Exception {
        CompletableFuture<Boolean> result = adapter.requestConfirmation("chat1", "shell", "echo hello");

        String confirmationId = extractConfirmationId(0); // Confirm button

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, "chat1", "42"));
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void confirmationDenied() throws Exception {
        CompletableFuture<Boolean> result = adapter.requestConfirmation("chat1", "shell", "rm -rf test");

        String confirmationId = extractConfirmationId(1); // Cancel button

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, false, "chat1", "42"));
        assertFalse(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void confirmationTimeout() throws Exception {
        BotProperties props = new BotProperties();
        props.getSecurity().getToolConfirmation().setEnabled(true);
        props.getSecurity().getToolConfirmation().setTimeoutSeconds(1);

        TelegramConfirmationAdapter shortTimeoutAdapter = new TelegramConfirmationAdapter(props);
        shortTimeoutAdapter.setTelegramClient(telegramClient);

        CompletableFuture<Boolean> result = shortTimeoutAdapter.requestConfirmation("chat1", "shell", "test");

        Boolean approved = result.get(3, TimeUnit.SECONDS);
        assertFalse(approved);
    }

    @Test
    void unknownConfirmationIdIsIgnored() {
        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent("unknown-id", true, "chat1", "42"));
        // Should not throw, just log debug
    }

    @Test
    void callbackUpdatesMessage() throws Exception {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenReturn(null);

        CompletableFuture<Boolean> result = adapter.requestConfirmation("chat1", "shell", "test");
        String confirmationId = extractConfirmationId(0);

        adapter.onConfirmationCallback(
                new ConfirmationCallbackEvent(confirmationId, true, "chat1", "99"));

        result.get(1, TimeUnit.SECONDS);

        ArgumentCaptor<EditMessageText> editCaptor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(editCaptor.capture());
        EditMessageText edit = editCaptor.getValue();
        assertEquals("chat1", edit.getChatId());
        assertEquals(99, edit.getMessageId());
        assertTrue(edit.getText().contains("Confirmed"));
    }
}
