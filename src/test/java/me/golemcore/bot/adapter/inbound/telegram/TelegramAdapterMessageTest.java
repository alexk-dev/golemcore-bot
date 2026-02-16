package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.security.AllowlistValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAdapterMessageTest {

    private static final String CHAT_ID = "123";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String USER_ID = "user1";

    private TelegramAdapter adapter;
    private TelegramClient telegramClient;
    private AllowlistValidator allowlistValidator;

    @BeforeEach
    void setUp() {
        allowlistValidator = mock(AllowlistValidator.class);
        telegramClient = mock(TelegramClient.class);

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.getTelegramToken()).thenReturn("test-token");

        adapter = new TelegramAdapter(
                runtimeConfigService,
                allowlistValidator,
                mock(ApplicationEventPublisher.class),
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                mock(MessageService.class),
                new TestObjectProvider<>(mock(CommandPort.class)),
                mock(TelegramVoiceHandler.class),
                mock(TelegramMenuHandler.class));
        adapter.setTelegramClient(telegramClient);
    }

    // ===== sendMessage =====

    @Test
    void shouldSendShortMessage() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendMessage(CHAT_ID, "Hello!");
        future.get();

        verify(telegramClient).execute(any(SendMessage.class));
    }

    @Test
    void shouldSendLongMessageInChunks() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        String longMessage = "A".repeat(5000);
        CompletableFuture<Void> future = adapter.sendMessage(CHAT_ID, longMessage);
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
        CompletableFuture<Void> future = adapter.sendPhoto(CHAT_ID, imageData, "photo.png", "A caption");
        future.get();

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    @Test
    void shouldSendPhotoWithoutCaption() throws Exception {
        when(telegramClient.execute(any(SendPhoto.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] imageData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendPhoto(CHAT_ID, imageData, "photo.png", null);
        future.get();

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    @Test
    void shouldSendPhotoWithBlankCaption() throws Exception {
        when(telegramClient.execute(any(SendPhoto.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] imageData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendPhoto(CHAT_ID, imageData, "photo.png", "  ");
        future.get();

        verify(telegramClient).execute(any(SendPhoto.class));
    }

    // ===== sendDocument =====

    @Test
    void shouldSendDocument() throws Exception {
        when(telegramClient.execute(any(SendDocument.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] fileData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendDocument(CHAT_ID, fileData, "report.pdf", "PDF report");
        future.get();

        verify(telegramClient).execute(any(SendDocument.class));
    }

    @Test
    void shouldSendDocumentWithoutCaption() throws Exception {
        when(telegramClient.execute(any(SendDocument.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] fileData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendDocument(CHAT_ID, fileData, "report.pdf", null);
        future.get();

        verify(telegramClient).execute(any(SendDocument.class));
    }

    // ===== sendVoice =====

    @Test
    void shouldSendVoice() throws Exception {
        when(telegramClient.execute(any(SendVoice.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        byte[] voiceData = new byte[] { 1, 2, 3 };
        CompletableFuture<Void> future = adapter.sendVoice(CHAT_ID, voiceData);
        future.get();

        verify(telegramClient).execute(any(SendVoice.class));
    }

    // ===== showTyping =====

    @Test
    void shouldShowTyping() throws Exception {
        when(telegramClient.execute(any(SendChatAction.class)))
                .thenReturn(true);

        adapter.showTyping(CHAT_ID);

        verify(telegramClient).execute(any(SendChatAction.class));
    }

    @Test
    void shouldHandleTypingFailureGracefully() throws Exception {
        when(telegramClient.execute(any(SendChatAction.class)))
                .thenThrow(new TelegramApiException("Network error"));

        assertDoesNotThrow(() -> adapter.showTyping(CHAT_ID));
    }

    // ===== isAuthorized =====

    @Test
    void shouldAuthorizeAllowedUser() {
        when(allowlistValidator.isAllowed(CHANNEL_TELEGRAM, USER_ID)).thenReturn(true);
        when(allowlistValidator.isBlocked(USER_ID)).thenReturn(false);

        assertTrue(adapter.isAuthorized(USER_ID));
    }

    @Test
    void shouldDenyBlockedUser() {
        when(allowlistValidator.isAllowed(CHANNEL_TELEGRAM, USER_ID)).thenReturn(true);
        when(allowlistValidator.isBlocked(USER_ID)).thenReturn(true);

        assertFalse(adapter.isAuthorized(USER_ID));
    }

    @Test
    void shouldDenyNonAllowedUser() {
        when(allowlistValidator.isAllowed(CHANNEL_TELEGRAM, USER_ID)).thenReturn(false);
        when(allowlistValidator.isBlocked(USER_ID)).thenReturn(false);

        assertFalse(adapter.isAuthorized(USER_ID));
    }

    // ===== getChannelType =====

    @Test
    void shouldReturnTelegramChannelType() {
        assertEquals(CHANNEL_TELEGRAM, adapter.getChannelType());
    }

    // ===== onMessage =====

    @Test
    void shouldRegisterMessageHandler() {
        assertDoesNotThrow(() -> adapter.onMessage(msg -> {
        }));
    }

    // ===== truncateCaption via ReflectionTestUtils =====

    @Test
    void shouldTruncateLongCaption() {
        String longCaption = "A".repeat(2000);
        String result = ReflectionTestUtils.invokeMethod(adapter, "truncateCaption", longCaption);

        assertTrue(result.length() <= 1024);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void shouldNotTruncateShortCaption() {
        String result = ReflectionTestUtils.invokeMethod(adapter, "truncateCaption", "Short caption");
        assertEquals("Short caption", result);
    }

    // ===== truncateForLog =====

    @Test
    void shouldTruncateForLog() {
        String longText = "A".repeat(200);
        String result = ReflectionTestUtils.invokeMethod(adapter, "truncateForLog", longText, 50);

        assertTrue(result.length() <= 53); // 50 + "..."
        assertTrue(result.endsWith("..."));
    }

    @Test
    void shouldNotTruncateShortTextForLog() {
        String result = ReflectionTestUtils.invokeMethod(adapter, "truncateForLog", "Short", 50);
        assertEquals("Short", result);
    }

    @Test
    void shouldHandleNullTextForLog() {
        String result = ReflectionTestUtils.invokeMethod(adapter, "truncateForLog", (String) null, 50);
        assertNull(result);
    }

    // ===== isVoiceForbidden =====

    @Test
    void shouldDetectVoiceForbidden() {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");

        Boolean forbidden = ReflectionTestUtils.invokeMethod(adapter, "isVoiceForbidden", ex);
        assertTrue(forbidden);
    }

    @Test
    void shouldDetectVoiceForbiddenInCause() {
        TelegramApiRequestException innerEx = mock(TelegramApiRequestException.class);
        when(innerEx.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");

        Exception wrapper = new RuntimeException("Wrapper", innerEx);

        Boolean forbidden = ReflectionTestUtils.invokeMethod(adapter, "isVoiceForbidden", wrapper);
        assertTrue(forbidden);
    }

    @Test
    void shouldNotDetectVoiceForbiddenForOtherErrors() {
        Boolean forbidden = ReflectionTestUtils.invokeMethod(adapter, "isVoiceForbidden",
                new RuntimeException("Network error"));
        assertFalse(forbidden);
    }

    // ===== setTelegramClient / getTelegramClient =====

    @Test
    void shouldSetAndGetTelegramClient() {
        TelegramClient newClient = mock(TelegramClient.class);
        adapter.setTelegramClient(newClient);
        assertSame(newClient, adapter.getTelegramClient());
    }
}
