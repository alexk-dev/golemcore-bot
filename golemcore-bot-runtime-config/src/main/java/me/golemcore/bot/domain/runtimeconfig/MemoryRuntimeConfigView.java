package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DECAY_DAYS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DECAY_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_DETAIL_MIN_SCORE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_HINTS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_DISCLOSURE_MODE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_EPISODIC_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROCEDURAL_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROMOTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_PROMPT_STYLE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_RERANKING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_RERANKING_PROFILE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_SEMANTIC_TOP_K;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_TOOL_EXPANSION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_VERSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_MEMORY_WORKING_TOP_K;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface MemoryRuntimeConfigView extends RuntimeConfigSource {
    default boolean isMemoryEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return true;
        }
        Boolean val = memoryConfig.getEnabled();
        return val != null ? val : true;
    }

    default int getMemorySoftPromptBudgetTokens() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
        }
        Integer val = memoryConfig.getSoftPromptBudgetTokens();
        return val != null ? val : DEFAULT_MEMORY_SOFT_PROMPT_BUDGET_TOKENS;
    }

    default int getMemoryVersion() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_VERSION;
        }
        Integer val = memoryConfig.getVersion();
        return val != null ? val : DEFAULT_MEMORY_VERSION;
    }

    default int getMemoryMaxPromptBudgetTokens() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS;
        }
        Integer val = memoryConfig.getMaxPromptBudgetTokens();
        return val != null ? val : DEFAULT_MEMORY_MAX_PROMPT_BUDGET_TOKENS;
    }

    default int getMemoryWorkingTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_WORKING_TOP_K;
        }
        Integer val = memoryConfig.getWorkingTopK();
        return val != null ? val : DEFAULT_MEMORY_WORKING_TOP_K;
    }

    default int getMemoryEpisodicTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_EPISODIC_TOP_K;
        }
        Integer val = memoryConfig.getEpisodicTopK();
        return val != null ? val : DEFAULT_MEMORY_EPISODIC_TOP_K;
    }

    default int getMemorySemanticTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_SEMANTIC_TOP_K;
        }
        Integer val = memoryConfig.getSemanticTopK();
        return val != null ? val : DEFAULT_MEMORY_SEMANTIC_TOP_K;
    }

    default int getMemoryProceduralTopK() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_PROCEDURAL_TOP_K;
        }
        Integer val = memoryConfig.getProceduralTopK();
        return val != null ? val : DEFAULT_MEMORY_PROCEDURAL_TOP_K;
    }

    default boolean isMemoryPromotionEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_PROMOTION_ENABLED;
        }
        Boolean val = memoryConfig.getPromotionEnabled();
        return val != null ? val : DEFAULT_MEMORY_PROMOTION_ENABLED;
    }

    default double getMemoryPromotionMinConfidence() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE;
        }
        Double val = memoryConfig.getPromotionMinConfidence();
        return val != null ? val : DEFAULT_MEMORY_PROMOTION_MIN_CONFIDENCE;
    }

    default boolean isMemoryDecayEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_DECAY_ENABLED;
        }
        Boolean val = memoryConfig.getDecayEnabled();
        return val != null ? val : DEFAULT_MEMORY_DECAY_ENABLED;
    }

    default int getMemoryDecayDays() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_DECAY_DAYS;
        }
        Integer val = memoryConfig.getDecayDays();
        return val != null ? val : DEFAULT_MEMORY_DECAY_DAYS;
    }

    default int getMemoryRetrievalLookbackDays() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS;
        }
        Integer val = memoryConfig.getRetrievalLookbackDays();
        return val != null ? val : DEFAULT_MEMORY_RETRIEVAL_LOOKBACK_DAYS;
    }

    default boolean isMemoryCodeAwareExtractionEnabled() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null) {
            return DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
        }
        Boolean val = memoryConfig.getCodeAwareExtractionEnabled();
        return val != null ? val : DEFAULT_MEMORY_CODE_AWARE_EXTRACTION_ENABLED;
    }

    default String getMemoryDisclosureMode() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        String val = disclosureConfig.getMode();
        return val != null ? val : DEFAULT_MEMORY_DISCLOSURE_MODE;
    }

    default String getMemoryPromptStyle() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        String val = disclosureConfig.getPromptStyle();
        return val != null ? val : DEFAULT_MEMORY_PROMPT_STYLE;
    }

    default boolean isMemoryToolExpansionEnabled() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        Boolean val = disclosureConfig.getToolExpansionEnabled();
        return val != null ? val : DEFAULT_MEMORY_TOOL_EXPANSION_ENABLED;
    }

    default boolean isMemoryDisclosureHintsEnabled() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        Boolean val = disclosureConfig.getDisclosureHintsEnabled();
        return val != null ? val : DEFAULT_MEMORY_DISCLOSURE_HINTS_ENABLED;
    }

    default double getMemoryDetailMinScore() {
        RuntimeConfig.MemoryDisclosureConfig disclosureConfig = getMemoryDisclosureConfig();
        Double val = disclosureConfig.getDetailMinScore();
        return val != null ? val : DEFAULT_MEMORY_DISCLOSURE_DETAIL_MIN_SCORE;
    }

    default boolean isMemoryRerankingEnabled() {
        RuntimeConfig.MemoryRerankingConfig rerankingConfig = getMemoryRerankingConfig();
        Boolean val = rerankingConfig.getEnabled();
        return val != null ? val : DEFAULT_MEMORY_RERANKING_ENABLED;
    }

    default String getMemoryRerankingProfile() {
        RuntimeConfig.MemoryRerankingConfig rerankingConfig = getMemoryRerankingConfig();
        String val = rerankingConfig.getProfile();
        return val != null ? val : DEFAULT_MEMORY_RERANKING_PROFILE;
    }

    default String getMemoryDiagnosticsVerbosity() {
        RuntimeConfig.MemoryDiagnosticsConfig diagnosticsConfig = getMemoryDiagnosticsConfig();
        String val = diagnosticsConfig.getVerbosity();
        return val != null ? val : DEFAULT_MEMORY_DIAGNOSTICS_VERBOSITY;
    }

    private RuntimeConfig.MemoryDisclosureConfig getMemoryDisclosureConfig() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null || memoryConfig.getDisclosure() == null) {
            return RuntimeConfig.MemoryDisclosureConfig.builder().build();
        }
        return memoryConfig.getDisclosure();
    }

    private RuntimeConfig.MemoryDiagnosticsConfig getMemoryDiagnosticsConfig() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null || memoryConfig.getDiagnostics() == null) {
            return RuntimeConfig.MemoryDiagnosticsConfig.builder().build();
        }
        return memoryConfig.getDiagnostics();
    }

    private RuntimeConfig.MemoryRerankingConfig getMemoryRerankingConfig() {
        RuntimeConfig.MemoryConfig memoryConfig = getRuntimeConfig().getMemory();
        if (memoryConfig == null || memoryConfig.getReranking() == null) {
            return RuntimeConfig.MemoryRerankingConfig.builder().build();
        }
        return memoryConfig.getReranking();
    }
}
