package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.plugin.runtime.TelegramWebhookUpdateBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramWebhookControllerWebTest {

    private RuntimeConfigService runtimeConfigService;
    private TelegramWebhookUpdateBridge updateBridge;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        updateBridge = mock(TelegramWebhookUpdateBridge.class);
        TelegramWebhookController controller = new TelegramWebhookController(runtimeConfigService, updateBridge);

        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    void shouldRouteTelegramUpdateToBridgeWhenSecretTokenMatches() {
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .webhookSecretToken("telegram-secret")
                        .build())
                .build());
        doReturn(CompletableFuture.completedFuture(null)).when(updateBridge)
                .dispatch(org.mockito.ArgumentMatchers.any());

        webTestClient.post()
                .uri("/api/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token", "telegram-secret")
                .bodyValue("{\"update_id\":123}")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(updateBridge).dispatch(captor.capture());
        assertNotNull(captor.getValue());
        assertEquals(123, captor.getValue().getUpdateId());
    }

    @Test
    void shouldRejectTelegramUpdateWhenSecretTokenDoesNotMatch() {
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .webhookSecretToken("telegram-secret")
                        .build())
                .build());

        webTestClient.post()
                .uri("/api/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"update_id\":123}")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(updateBridge, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldAcceptTelegramUpdateWithoutHeaderWhenConfiguredTokenIsBlank() {
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .webhookSecretToken("   ")
                        .build())
                .build());
        doReturn(CompletableFuture.completedFuture(null)).when(updateBridge)
                .dispatch(org.mockito.ArgumentMatchers.any());

        webTestClient.post()
                .uri("/api/telegram/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"update_id\":321}")
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(updateBridge).dispatch(captor.capture());
        assertEquals(321, captor.getValue().getUpdateId());
    }
}
