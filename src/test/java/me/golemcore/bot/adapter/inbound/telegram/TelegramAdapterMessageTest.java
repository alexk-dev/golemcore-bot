package me.golemcore.bot.adapter.inbound.telegram;

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
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramAdapterMessageTest {

    private TelegramAdapter adapter;
    private TelegramClient telegramClient;
    private AllowlistValidator allowlistValidator;

    @BeforeEach
    void setUp() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        when(properties.getChannels()).thenReturn(Map.of("telegram", telegramProps));

        allowlistValidator = mock(AllowlistValidator.class);
        telegramClient = mock(TelegramClient.class);

        adapter = new TelegramAdapter(
                properties,
                allowlistValidator,
                mock(ApplicationEventPublisher.class),
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                mock(MessageService.class),
                mock(CommandPort.class),
                mock(TelegramVoiceHandler.class));
        adapter.setTelegramClient(telegramClient);
    }

    // ===== sendMessage =====

    @Test
    void shouldSendShortMessage() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendMessage("123", "Hello!");
        future.get();

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void shouldSendLongMessageInChunks() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        String longMessage = "A".repeat(5000);
        CompletableFuture<Void> future = adapter.sendMessage("123", longMessage);
        future.get();

        // Should be split into multiple messages
        verify(telegramClient, atLeast(2)).execute(any(SendMessage.class));
    }

    // ===== sendPhoto =====

    @Test
    void shouldSendPhoto() throws Exception {
        when(telegramClient.execute(any(SendPhoto.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] imageData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendPhoto("123", imageData, "photo.png", "A caption");
        future.get();

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    @Test
    void shouldSendPhotoWithoutCaption() throws Exception {
        when(telegramClient.execute(any(SendPhoto.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] imageData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendPhoto("123", imageData, "photo.png", null);
        future.get();

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    @Test
    void shouldSendPhotoWithBlankCaption() throws Exception {
        when(telegramClient.execute(any(SendPhoto.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] imageData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendPhoto("123", imageData, "photo.png", "  ");
        future.get();

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    // ===== sendDocument =====

    @Test
    void shouldSendDocument() throws Exception {
        when(telegramClient.execute(any(SendDocument.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] fileData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendDocument("123", fileData, "report.pdf", "PDF report");
        future.get();

        verify(telegramClient).execute(any(SendDocument.class));
    }

    @Test
    void shouldSendDocumentWithoutCaption() throws Exception {
        when(telegramClient.execute(any(SendDocument.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] fileData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendDocument("123", fileData, "report.pdf", null);
        future.get();

        verify(telegramClient).execute(any(SendDocument.class));
    }

    // ===== sendVoice =====

    @Test
    void shouldSendVoice() throws Exception {
        when(telegramClient.execute(any(SendVoice.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] voiceData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendVoice("123", voiceData);
        future.get();

        verify(telegramClient).execute(any(SendVoice.class));
    }

    // ===== showTyping =====

    @Test
    void shouldShowTyping() throws Exception {
        when(telegramClient.execute(any(SendChatAction.class)))
                .thenReturn(true);

        adapter.showTyping("123");

        verify(telegramClient).execute(any(SendChatAction.class));
    }

    @Test
    void shouldHandleTypingFailureGracefully() throws Exception {
        when(telegramClient.execute(any(SendChatAction.class)))
                .thenThrow(new TelegramApiException("Network error"));

        assertDoesNotThrow(() -> adapter.showTyping("123"));
    }

    // ===== isAuthorized =====

    @Test
    void shouldAuthorizeAllowedUser() {
        when(allowlistValidator.isAllowed("telegram", "user1")).thenReturn(true);
        when(allowlistValidator.isBlocked("user1")).thenReturn(false);

        assertTrue(adapter.isAuthorized("user1"));
    }

    @Test
    void shouldDenyBlockedUser() {
        when(allowlistValidator.isAllowed("telegram", "user1")).thenReturn(true);
        when(allowlistValidator.isBlocked("user1")).thenReturn(true);

        assertFalse(adapter.isAuthorized("user1"));
    }

    @Test
    void shouldDenyNonAllowedUser() {
        when(allowlistValidator.isAllowed("telegram", "user1")).thenReturn(false);
        when(allowlistValidator.isBlocked("user1")).thenReturn(false);

        assertFalse(adapter.isAuthorized("user1"));
    }

    // ===== getChannelType =====

    @Test
    void shouldReturnTelegramChannelType() {
        assertEquals("telegram", adapter.getChannelType());
    }

    // ===== onMessage =====

    @Test
    void shouldRegisterMessageHandler() {
        assertDoesNotThrow(() -> adapter.onMessage(msg -> {
        }));
    }

    // ===== truncateCaption via reflection =====

    @Test
    void shouldTruncateLongCaption() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("truncateCaption", String.class);
        method.setAccessible(true);

        String longCaption = "A".repeat(2000);
        String result = (String) method.invoke(adapter, longCaption);

        assertTrue(result.length() <= 1024);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void shouldNotTruncateShortCaption() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("truncateCaption", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(adapter, "Short caption");
        assertEquals("Short caption", result);
    }

    // ===== truncateForLog =====

    @Test
    void shouldTruncateForLog() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("truncateForLog", String.class,
                int.class);
        method.setAccessible(true);

        String longText = "A".repeat(200);
        String result = (String) method.invoke(null, longText, 50);

        assertTrue(result.length() <= 53); // 50 + "..."
        assertTrue(result.endsWith("..."));
    }

    @Test
    void shouldNotTruncateShortTextForLog() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("truncateForLog", String.class,
                int.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "Short", 50);
        assertEquals("Short", result);
    }

    @Test
    void shouldHandleNullTextForLog() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("truncateForLog", String.class,
                int.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, null, 50);
        assertNull(result);
    }

    // ===== isVoiceForbidden =====

    @Test
    void shouldDetectVoiceForbidden() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("isVoiceForbidden", Exception.class);
        method.setAccessible(true);

        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");

        assertTrue((boolean) method.invoke(adapter, ex));
    }

    @Test
    void shouldDetectVoiceForbiddenInCause() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("isVoiceForbidden", Exception.class);
        method.setAccessible(true);

        TelegramApiRequestException innerEx = mock(TelegramApiRequestException.class);
        when(innerEx.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");

        Exception wrapper = new RuntimeException("Wrapper", innerEx);

        assertTrue((boolean) method.invoke(adapter, wrapper));
    }

    @Test
    void shouldNotDetectVoiceForbiddenForOtherErrors() throws Exception {
        java.lang.reflect.Method method = TelegramAdapter.class.getDeclaredMethod("isVoiceForbidden", Exception.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(adapter, new RuntimeException("Network error")));
    }

    // ===== setTelegramClient / getTelegramClient =====

    @Test
    void shouldSetAndGetTelegramClient() {
        TelegramClient newClient = mock(TelegramClient.class);
        adapter.setTelegramClient(newClient);
        assertSame(newClient, adapter.getTelegramClient());
    }
}
