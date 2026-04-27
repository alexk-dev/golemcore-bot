package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigRedactorTest {

    private final RuntimeConfigRedactor redactor = new RuntimeConfigRedactor();

    @Test
    void shouldRedactAllKnownSecretsForApiView() {
        RuntimeConfig cfg = RuntimeConfig.builder().build();
        cfg.getTelegram().setToken(Secret.of("telegram-token"));
        cfg.getVoice().setApiKey(Secret.of("voice-token"));
        cfg.getVoice().setWhisperSttApiKey(Secret.of("whisper-token"));

        RuntimeConfig.LlmProviderConfig llmProvider = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("llm-token")).build();
        cfg.getLlm().setProviders(new LinkedHashMap<>());
        cfg.getLlm().getProviders().put("default", llmProvider);

        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddings = RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig
                .builder().apiKey(Secret.of("embeddings-token")).build();
        cfg.setSelfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder().embeddings(embeddings).build())
                        .build())
                .build());

        redactor.redactSecrets(cfg);

        assertNull(cfg.getTelegram().getToken().getValue());
        assertNull(cfg.getVoice().getApiKey().getValue());
        assertNull(cfg.getVoice().getWhisperSttApiKey().getValue());
        assertNull(cfg.getLlm().getProviders().get("default").getApiKey().getValue());
        assertNull(embeddings.getApiKey().getValue());
        assertTrue(cfg.getTelegram().getToken().getPresent());
        assertTrue(embeddings.getApiKey().getPresent());
    }
}
