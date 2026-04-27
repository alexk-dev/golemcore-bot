package me.golemcore.bot.adapter.inbound.telegram;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.plugin.runtime.TelegramWebhookUpdateBridge;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.concurrent.CompletableFuture;

/**
 * Dedicated Telegram webhook endpoint for bot-side update ingestion.
 */
@RestController
@RequestMapping("/api/telegram")
public class TelegramWebhookController {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final RuntimeConfigService runtimeConfigService;
    private final TelegramWebhookUpdateBridge updateBridge;

    public TelegramWebhookController(
            RuntimeConfigService runtimeConfigService,
            TelegramWebhookUpdateBridge updateBridge) {
        this.runtimeConfigService = runtimeConfigService;
        this.updateBridge = updateBridge;
    }

    @PostMapping("/webhook")
    public Mono<ResponseEntity<Void>> webhook(
            @RequestBody Update update,
            @RequestHeader(name = SECRET_HEADER, required = false) String secretToken) {

        RuntimeConfig.TelegramConfig telegramConfig = runtimeConfigService.getRuntimeConfig().getTelegram();
        String configuredSecret = telegramConfig != null ? telegramConfig.getWebhookSecretToken() : null;
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            if (secretToken == null || !configuredSecret.equals(secretToken)) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
        }

        CompletableFuture<Void> delivery = updateBridge.dispatch(update);
        return Mono.fromFuture(delivery).thenReturn(ResponseEntity.ok().build());
    }
}
