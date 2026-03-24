package me.golemcore.bot.domain.model;

/**
 * Canonical provider-metadata keys shared between LLM adapters and downstream
 * pipeline systems.
 */
public final class LlmProviderMetadataKeys {

    public static final String TOOL_ATTACHMENT_FALLBACK_APPLIED = "toolAttachmentFallbackApplied";
    public static final String TOOL_ATTACHMENT_FALLBACK_REASON = "toolAttachmentFallbackReason";
    public static final String TOOL_ATTACHMENT_FALLBACK_REASON_OVERSIZE_INVALID_JSON = "oversize_invalid_json";

    private LlmProviderMetadataKeys() {
    }
}
