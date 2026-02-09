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
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramAdapterVoiceTest {

    private TelegramAdapter adapter;
    private TelegramClient telegramClient;

    @BeforeEach
    void setUp() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        when(properties.getChannels()).thenReturn(java.util.Map.of("telegram", telegramProps));

        telegramClient = mock(TelegramClient.class);

        adapter = new TelegramAdapter(
                properties,
                mock(AllowlistValidator.class),
                mock(ApplicationEventPublisher.class),
                mock(TelegramBotsLongPollingApplication.class),
                mock(UserPreferencesService.class),
                mock(MessageService.class),
                mock(CommandPort.class),
                mock(TelegramVoiceHandler.class));
        adapter.setTelegramClient(telegramClient);
    }

    @Test
    void sendVoice_success() throws Exception {
        when(telegramClient.execute(any(SendVoice.class))).thenReturn(null);

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1, 2, 3 });
        future.get();

        ArgumentCaptor<SendVoice> captor = ArgumentCaptor.forClass(SendVoice.class);
        verify(telegramClient).execute(captor.capture());
        assertEquals("123", captor.getValue().getChatId());
    }

    @Test
    void sendVoice_genericExceptionPropagates() throws Exception {
        when(telegramClient.execute(any(SendVoice.class)))
                .thenThrow(new RuntimeException("network error"));

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1 });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("Failed to send voice"));
    }

    @Test
    void sendVoice_voiceForbiddenFallsBackToAudio() throws Exception {
        TelegramApiRequestException forbiddenEx = mock(TelegramApiRequestException.class);
        when(forbiddenEx.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");

        when(telegramClient.execute(any(SendVoice.class))).thenThrow(forbiddenEx);
        when(telegramClient.execute(any(SendAudio.class))).thenReturn(null);

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1, 2 });
        future.get();

        verify(telegramClient).execute(any(SendVoice.class));
        verify(telegramClient).execute(any(SendAudio.class));
    }

    @Test
    void sendVoice_voiceForbiddenViaCauseFallsBackToAudio() throws Exception {
        TelegramApiRequestException forbiddenEx = mock(TelegramApiRequestException.class);
        when(forbiddenEx.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");
        RuntimeException wrapper = new RuntimeException("wrapped", forbiddenEx);

        when(telegramClient.execute(any(SendVoice.class))).thenThrow(wrapper);
        when(telegramClient.execute(any(SendAudio.class))).thenReturn(null);

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1 });
        future.get();

        verify(telegramClient).execute(any(SendAudio.class));
    }

    @Test
    void sendVoice_audioFallbackAlsoFails() throws Exception {
        TelegramApiRequestException forbiddenEx = mock(TelegramApiRequestException.class);
        when(forbiddenEx.getApiResponse()).thenReturn("VOICE_MESSAGES_FORBIDDEN");

        when(telegramClient.execute(any(SendVoice.class))).thenThrow(forbiddenEx);
        when(telegramClient.execute(any(SendAudio.class)))
                .thenThrow(new RuntimeException("audio also failed"));

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1 });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("Failed to send audio"));
    }

    @Test
    void sendVoice_nonForbiddenApiErrorDoesNotFallBack() throws Exception {
        TelegramApiRequestException apiEx = mock(TelegramApiRequestException.class);
        when(apiEx.getApiResponse()).thenReturn("BAD_REQUEST: chat not found");

        when(telegramClient.execute(any(SendVoice.class))).thenThrow(apiEx);

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1 });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("Failed to send voice"));

        verify(telegramClient, never()).execute(any(SendAudio.class));
    }

    @Test
    void sendVoice_apiResponseNullDoesNotFallBack() throws Exception {
        TelegramApiRequestException apiEx = mock(TelegramApiRequestException.class);
        when(apiEx.getApiResponse()).thenReturn(null);

        when(telegramClient.execute(any(SendVoice.class))).thenThrow(apiEx);

        CompletableFuture<Void> future = adapter.sendVoice("123", new byte[] { 1 });
        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("Failed to send voice"));

        verify(telegramClient, never()).execute(any(SendAudio.class));
    }
}
