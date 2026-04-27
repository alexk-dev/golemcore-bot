package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;

/**
 * Produces API-safe runtime configuration views by masking all known secret fields.
 */
public class RuntimeConfigRedactor {

    public void redactSecrets(RuntimeConfig cfg) {
        if (cfg == null) {
            return;
        }
        cfg.getTelegram().setToken(Secret.redacted(cfg.getTelegram().getToken()));
        cfg.getVoice().setApiKey(Secret.redacted(cfg.getVoice().getApiKey()));
        cfg.getVoice().setWhisperSttApiKey(Secret.redacted(cfg.getVoice().getWhisperSttApiKey()));

        if (cfg.getLlm() != null && cfg.getLlm().getProviders() != null) {
            for (RuntimeConfig.LlmProviderConfig providerConfig : cfg.getLlm().getProviders().values()) {
                if (providerConfig != null) {
                    providerConfig.setApiKey(Secret.redacted(providerConfig.getApiKey()));
                }
            }
        }
        redactSelfEvolvingEmbeddingsApiKey(cfg);
    }

    private void redactSelfEvolvingEmbeddingsApiKey(RuntimeConfig cfg) {
        if (cfg.getSelfEvolving() == null || cfg.getSelfEvolving().getTactics() == null
                || cfg.getSelfEvolving().getTactics().getSearch() == null
                || cfg.getSelfEvolving().getTactics().getSearch().getEmbeddings() == null) {
            return;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddings = cfg.getSelfEvolving().getTactics().getSearch()
                .getEmbeddings();
        embeddings.setApiKey(Secret.redacted(embeddings.getApiKey()));
    }
}
