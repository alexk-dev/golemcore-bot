package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.security.AllowlistValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramAdapterRetryTest {

    private static final String CHAT_ID = "123";

    private TelegramAdapter adapter;
    private TelegramClient telegramClient;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.getTelegramToken()).thenReturn("test-token");

        TelegramAdapter realAdapter = new TelegramAdapter(
                runtimeConfigService,
                mock(AllowlistValidator.class),
                mock(ApplicationEventPublisher.class),
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                mock(MessageService.class),
                new TestObjectProvider<>(mock(CommandPort.class)),
                mock(TelegramVoiceHandler.class),
                mock(TelegramMenuHandler.class));

        adapter = spy(realAdapter);
        doNothing().when(adapter).sleepForRetry(anyInt());
        adapter.setTelegramClient(telegramClient);
    }

    // ===== executeWithRetry =====

    @Test
    void shouldRetryOn429AndSucceedOnSecondAttempt() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(5);

        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(rateLimitEx)
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendMessage(CHAT_ID, "Hello");
        future.get();

        verify(telegramClient, times(2)).execute(any(SendMessage.class));
        verify(adapter).sleepForRetry(5);
    }

    @Test
    void shouldRetryMultipleTimesOn429() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(3);

        when(telegramClient.execute(any(SendMessage.class)))
                .thenThrow(rateLimitEx)
                .thenThrow(rateLimitEx)
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendMessage(CHAT_ID, "Hello");
        future.get();

        verify(telegramClient, times(3)).execute(any(SendMessage.class));
        verify(adapter, times(2)).sleepForRetry(3);
    }

    @Test
    void shouldGiveUpAfterMaxRetries() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(1);

        when(telegramClient.execute(any(SendVoice.class)))
                .thenThrow(rateLimitEx);

        CompletableFuture<Void> future = adapter.sendVoice(CHAT_ID, new byte[] { 1 });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("Failed to send voice"));

        // 1 initial + 3 retries = 4 total attempts
        verify(telegramClient, times(4)).execute(any(SendVoice.class));
        verify(adapter, times(3)).sleepForRetry(1);
    }

    @Test
    void shouldNotRetryNon429Errors() throws Exception {
        TelegramApiRequestException badRequestEx = mock(TelegramApiRequestException.class);
        when(badRequestEx.getErrorCode()).thenReturn(400);
        when(badRequestEx.getMessage()).thenReturn("Bad Request: chat not found");

        when(telegramClient.execute(any(SendVoice.class)))
                .thenThrow(badRequestEx);

        CompletableFuture<Void> future = adapter.sendVoice(CHAT_ID, new byte[] { 1 });
        assertThrows(ExecutionException.class, future::get);

        verify(telegramClient, times(1)).execute(any(SendVoice.class));
        verify(adapter, times(0)).sleepForRetry(anyInt());
    }

    @Test
    void shouldNotRetryGenericTelegramApiException() throws Exception {
        when(telegramClient.execute(any(SendVoice.class)))
                .thenThrow(new TelegramApiException("Network error"));

        CompletableFuture<Void> future = adapter.sendVoice(CHAT_ID, new byte[] { 1 });
        assertThrows(ExecutionException.class, future::get);

        verify(telegramClient, times(1)).execute(any(SendVoice.class));
        verify(adapter, times(0)).sleepForRetry(anyInt());
    }

    // ===== extractRetryAfterSeconds =====

    @Test
    void shouldExtractRetryAfterFromResponseParameters() {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getErrorCode()).thenReturn(429);
        ResponseParameters params = new ResponseParameters(null, 9);
        when(ex.getParameters()).thenReturn(params);

        int retryAfter = adapter.extractRetryAfterSeconds(ex);
        assertEquals(9, retryAfter);
    }

    @Test
    void shouldExtractRetryAfterFromMessage() {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getErrorCode()).thenReturn(429);
        when(ex.getParameters()).thenReturn(null);
        when(ex.getMessage()).thenReturn("[429] Too Many Requests: retry after 7");

        int retryAfter = adapter.extractRetryAfterSeconds(ex);
        assertEquals(7, retryAfter);
    }

    @Test
    void shouldUseDefaultWhenRetryAfterNotAvailable() {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getErrorCode()).thenReturn(429);
        when(ex.getParameters()).thenReturn(null);
        when(ex.getMessage()).thenReturn("Too Many Requests");

        int retryAfter = adapter.extractRetryAfterSeconds(ex);
        assertEquals(5, retryAfter);
    }

    @Test
    void shouldCapRetryAfterToMaximum() {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getErrorCode()).thenReturn(429);
        ResponseParameters params = new ResponseParameters(null, 120);
        when(ex.getParameters()).thenReturn(params);

        int retryAfter = adapter.extractRetryAfterSeconds(ex);
        assertEquals(30, retryAfter);
    }

    @Test
    void shouldCapRetryAfterFromMessageToMaximum() {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getErrorCode()).thenReturn(429);
        when(ex.getParameters()).thenReturn(null);
        when(ex.getMessage()).thenReturn("retry after 600");

        int retryAfter = adapter.extractRetryAfterSeconds(ex);
        assertEquals(30, retryAfter);
    }

    // ===== Retry for different send methods =====

    @Test
    void shouldRetryVoiceOn429() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(2);

        when(telegramClient.execute(any(SendVoice.class)))
                .thenThrow(rateLimitEx)
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendVoice(CHAT_ID, new byte[] { 1, 2, 3 });
        future.get();

        verify(telegramClient, times(2)).execute(any(SendVoice.class));
        verify(adapter).sleepForRetry(2);
    }

    @Test
    void shouldRetryPhotoOn429() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(4);

        when(telegramClient.execute(any(SendPhoto.class)))
                .thenThrow(rateLimitEx)
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendPhoto(CHAT_ID, new byte[] { 1 }, "img.png", "test");
        future.get();

        verify(telegramClient, times(2)).execute(any(SendPhoto.class));
        verify(adapter).sleepForRetry(4);
    }

    @Test
    void shouldRetryDocumentOn429() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(6);

        when(telegramClient.execute(any(SendDocument.class)))
                .thenThrow(rateLimitEx)
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        CompletableFuture<Void> future = adapter.sendDocument(CHAT_ID, new byte[] { 1 }, "doc.pdf", null);
        future.get();

        verify(telegramClient, times(2)).execute(any(SendDocument.class));
        verify(adapter).sleepForRetry(6);
    }

    @Test
    void shouldRetryTypingOn429() throws Exception {
        TelegramApiRequestException rateLimitEx = createRateLimitException(1);

        when(telegramClient.execute(any(SendChatAction.class)))
                .thenThrow(rateLimitEx)
                .thenReturn(true);

        adapter.showTyping(CHAT_ID);

        verify(telegramClient, times(2)).execute(any(SendChatAction.class));
        verify(adapter).sleepForRetry(1);
    }

    // ===== Helper =====

    private TelegramApiRequestException createRateLimitException(int retryAfterSeconds) {
        TelegramApiRequestException ex = mock(TelegramApiRequestException.class);
        when(ex.getErrorCode()).thenReturn(429);
        when(ex.getMessage()).thenReturn(
                "[429] Too Many Requests: retry after " + retryAfterSeconds);
        ResponseParameters params = new ResponseParameters(null, retryAfterSeconds);
        when(ex.getParameters()).thenReturn(params);
        return ex;
    }
}
