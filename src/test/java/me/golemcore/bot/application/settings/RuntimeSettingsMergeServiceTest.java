package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.UserPreferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSettingsMergeServiceTest {

    private final RuntimeSettingsMergeService mergeService = new RuntimeSettingsMergeService();

    @Test
    void shouldMergeWebhookSecretsFromExistingPreferences() {
        UserPreferences.WebhookConfig current = UserPreferences.WebhookConfig.builder()
                .token(Secret.of("existing-token"))
                .mappings(List.of(UserPreferences.HookMapping.builder()
                        .name("build")
                        .hmacSecret(Secret.of("existing-hmac"))
                        .build()))
                .build();
        UserPreferences.WebhookConfig incoming = UserPreferences.WebhookConfig.builder()
                .token(Secret.builder().encrypted(true).build())
                .mappings(List.of(
                        UserPreferences.HookMapping.builder()
                                .name("build")
                                .hmacSecret(Secret.builder().build())
                                .build(),
                        UserPreferences.HookMapping.builder()
                                .name("deploy")
                                .hmacSecret(Secret.of("new-secret"))
                                .build()))
                .build();

        mergeService.mergeWebhookSecrets(current, incoming);

        assertEquals("existing-token", incoming.getToken().getValue());
        assertTrue(Boolean.TRUE.equals(incoming.getToken().getEncrypted()));
        assertEquals("existing-hmac", incoming.getMappings().getFirst().getHmacSecret().getValue());
        assertEquals("new-secret", incoming.getMappings().get(1).getHmacSecret().getValue());
    }

    @Test
    void shouldPreserveSelfEvolvingEmbeddingsApiKeyWhenMergingSections() {
        RuntimeConfig baseline = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                                .apiKey(Secret.of("baseline-key"))
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
        RuntimeConfig patch = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                                .apiKey(Secret.builder().build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        RuntimeConfig merged = mergeService.mergeRuntimeConfigSections(baseline, patch);

        assertEquals("baseline-key", merged.getSelfEvolving().getTactics().getSearch().getEmbeddings().getApiKey()
                .getValue());
    }

    @Test
    void shouldPreserveExistingResilienceConfigWhenPatchOmitsIt() {
        RuntimeConfig.ResilienceConfig baselineResilience = RuntimeConfig.ResilienceConfig.builder()
                .enabled(false)
                .hotRetryMaxAttempts(2)
                .degradationFallbackModelTier("smart")
                .build();
        RuntimeConfig baseline = RuntimeConfig.builder()
                .resilience(baselineResilience)
                .build();
        RuntimeConfig patch = RuntimeConfig.builder().build();

        RuntimeConfig merged = mergeService.mergeRuntimeConfigSections(baseline, patch);

        assertEquals(baselineResilience, merged.getResilience());
    }

    @Test
    void shouldApplyIncomingResilienceConfigWhenPatchProvidesIt() {
        RuntimeConfig.ResilienceConfig incomingResilience = RuntimeConfig.ResilienceConfig.builder()
                .enabled(false)
                .hotRetryMaxAttempts(3)
                .degradationFallbackModelTier("deep")
                .build();
        RuntimeConfig baseline = RuntimeConfig.builder().build();
        RuntimeConfig patch = RuntimeConfig.builder()
                .resilience(incomingResilience)
                .build();

        RuntimeConfig merged = mergeService.mergeRuntimeConfigSections(baseline, patch);

        assertEquals(incomingResilience, merged.getResilience());
    }

    @Test
    void shouldRetainExistingSecretWhenIncomingSecretHasNoValue() {
        Secret current = Secret.of("current-secret");
        Secret incoming = Secret.builder()
                .encrypted(true)
                .build();

        Secret merged = mergeService.mergeSecret(current, incoming);

        assertEquals("current-secret", merged.getValue());
        assertTrue(Boolean.TRUE.equals(merged.getEncrypted()));
        assertTrue(Boolean.TRUE.equals(merged.getPresent()));
    }

    @Test
    void shouldMergeRuntimeSecretsWhenCurrentConfigSectionsExist() {
        RuntimeConfig current = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .token(Secret.of("telegram-token"))
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .apiKey(Secret.of("voice-token"))
                        .whisperSttApiKey(Secret.of("whisper-token"))
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(java.util.Map.of("openai", RuntimeConfig.LlmProviderConfig.builder()
                                .apiKey(Secret.of("llm-token"))
                                .build()))
                        .build())
                .build();
        RuntimeConfig incoming = RuntimeConfig.builder()
                .telegram(RuntimeConfig.TelegramConfig.builder()
                        .token(Secret.builder().build())
                        .build())
                .voice(RuntimeConfig.VoiceConfig.builder()
                        .apiKey(Secret.builder().build())
                        .whisperSttApiKey(Secret.builder().build())
                        .build())
                .llm(RuntimeConfig.LlmConfig.builder()
                        .providers(new java.util.LinkedHashMap<>(java.util.Map.of(
                                "openai", RuntimeConfig.LlmProviderConfig.builder()
                                        .apiKey(Secret.builder().build())
                                        .build())))
                        .build())
                .build();

        mergeService.mergeRuntimeSecrets(current, incoming);

        assertEquals("telegram-token", incoming.getTelegram().getToken().getValue());
        assertEquals("voice-token", incoming.getVoice().getApiKey().getValue());
        assertEquals("whisper-token", incoming.getVoice().getWhisperSttApiKey().getValue());
        assertEquals("llm-token", incoming.getLlm().getProviders().get("openai").getApiKey().getValue());
        assertFalse(Boolean.FALSE.equals(incoming.getTelegram().getToken().getPresent()));
        assertNotNull(incoming.getLlm().getProviders());
    }
}
