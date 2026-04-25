package me.golemcore.bot.plugin.runtime.api;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit engine-to-plugin runtime API mapping.
 */
@Component
public class PluginRuntimeApiMapper {

    public me.golemcore.bot.domain.model.SessionIdentity toHostSessionIdentity(
            me.golemcore.plugin.api.runtime.model.SessionIdentity sessionIdentity) {
        return sessionIdentity == null
                ? null
                : new me.golemcore.bot.domain.model.SessionIdentity(
                        sessionIdentity.channelType(),
                        sessionIdentity.conversationKey());
    }

    public me.golemcore.plugin.api.runtime.model.Plan toPluginPlan(me.golemcore.bot.domain.model.Plan plan) {
        if (plan == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.Plan.builder()
                .id(plan.getId())
                .title(plan.getTitle())
                .description(plan.getDescription())
                .markdown(plan.getMarkdown())
                .status(toPluginPlanStatus(plan.getStatus()))
                .steps(toPluginPlanSteps(plan.getSteps()))
                .modelTier(plan.getModelTier())
                .channelType(plan.getChannelType())
                .chatId(plan.getChatId())
                .transportChatId(plan.getTransportChatId())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }

    public me.golemcore.plugin.api.runtime.model.RuntimeConfig toPluginRuntimeConfig(
            me.golemcore.bot.domain.model.RuntimeConfig runtimeConfig) {
        if (runtimeConfig == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.RuntimeConfig.builder()
                .telegram(toPluginTelegramConfig(runtimeConfig.getTelegram()))
                .voice(toPluginVoiceConfig(runtimeConfig.getVoice()))
                .build();
    }

    public me.golemcore.bot.domain.model.RuntimeConfig toHostRuntimeConfig(
            me.golemcore.plugin.api.runtime.model.RuntimeConfig runtimeConfig) {
        if (runtimeConfig == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.RuntimeConfig.builder()
                .telegram(toHostTelegramConfig(runtimeConfig.getTelegram()))
                .voice(toHostVoiceConfig(runtimeConfig.getVoice()))
                .build();
    }

    public me.golemcore.plugin.api.runtime.model.UserPreferences toPluginUserPreferences(
            me.golemcore.bot.domain.model.UserPreferences preferences) {
        if (preferences == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.UserPreferences.builder()
                .language(preferences.getLanguage())
                .notificationsEnabled(preferences.isNotificationsEnabled())
                .timezone(preferences.getTimezone())
                .modelTier(preferences.getModelTier())
                .tierForce(preferences.isTierForce())
                .tierOverrides(toPluginTierOverrides(preferences.getTierOverrides()))
                .webhooks(toPluginWebhookConfig(preferences.getWebhooks()))
                .build();
    }

    public me.golemcore.bot.domain.model.UserPreferences toHostUserPreferences(
            me.golemcore.plugin.api.runtime.model.UserPreferences preferences) {
        if (preferences == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.UserPreferences.builder()
                .language(preferences.getLanguage())
                .notificationsEnabled(preferences.isNotificationsEnabled())
                .timezone(preferences.getTimezone())
                .modelTier(preferences.getModelTier())
                .tierForce(preferences.isTierForce())
                .tierOverrides(toHostTierOverrides(preferences.getTierOverrides()))
                .webhooks(toHostWebhookConfig(preferences.getWebhooks()))
                .build();
    }

    public me.golemcore.plugin.api.runtime.model.RuntimeConfig.InviteCode toPluginInviteCode(
            me.golemcore.bot.domain.model.RuntimeConfig.InviteCode inviteCode) {
        if (inviteCode == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.RuntimeConfig.InviteCode.builder()
                .code(inviteCode.getCode())
                .used(inviteCode.isUsed())
                .createdAt(inviteCode.getCreatedAt())
                .build();
    }

    private me.golemcore.plugin.api.runtime.model.Plan.PlanStatus toPluginPlanStatus(
            me.golemcore.bot.domain.model.Plan.PlanStatus status) {
        return status == null ? null : me.golemcore.plugin.api.runtime.model.Plan.PlanStatus.valueOf(status.name());
    }

    private List<me.golemcore.plugin.api.runtime.model.PlanStep> toPluginPlanSteps(
            List<me.golemcore.bot.domain.model.PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(this::toPluginPlanStep)
                .toList();
    }

    private me.golemcore.plugin.api.runtime.model.PlanStep toPluginPlanStep(
            me.golemcore.bot.domain.model.PlanStep step) {
        if (step == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.PlanStep.builder()
                .id(step.getId())
                .planId(step.getPlanId())
                .toolName(step.getToolName())
                .description(step.getDescription())
                .toolArguments(copyMap(step.getToolArguments()))
                .order(step.getOrder())
                .status(toPluginStepStatus(step.getStatus()))
                .result(step.getResult())
                .createdAt(step.getCreatedAt())
                .executedAt(step.getExecutedAt())
                .build();
    }

    private me.golemcore.plugin.api.runtime.model.PlanStep.StepStatus toPluginStepStatus(
            me.golemcore.bot.domain.model.PlanStep.StepStatus status) {
        return status == null ? null : me.golemcore.plugin.api.runtime.model.PlanStep.StepStatus.valueOf(status.name());
    }

    private me.golemcore.plugin.api.runtime.model.RuntimeConfig.TelegramConfig toPluginTelegramConfig(
            me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig telegram) {
        if (telegram == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.RuntimeConfig.TelegramConfig.builder()
                .enabled(telegram.getEnabled())
                .token(toPluginSecret(telegram.getToken()))
                .authMode(telegram.getAuthMode())
                .transportMode(telegram.getTransportMode())
                .webhookSecretToken(telegram.getWebhookSecretToken())
                .conversationScope(telegram.getConversationScope())
                .aggregateIncomingMessages(telegram.getAggregateIncomingMessages())
                .aggregationDelayMs(telegram.getAggregationDelayMs())
                .mergeForwardedMessages(telegram.getMergeForwardedMessages())
                .mergeSequentialFragments(telegram.getMergeSequentialFragments())
                .allowedUsers(copyList(telegram.getAllowedUsers()))
                .inviteCodes(toPluginInviteCodes(telegram.getInviteCodes()))
                .build();
    }

    private me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig toHostTelegramConfig(
            me.golemcore.plugin.api.runtime.model.RuntimeConfig.TelegramConfig telegram) {
        if (telegram == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig.builder()
                .enabled(telegram.getEnabled())
                .token(toHostSecret(telegram.getToken()))
                .authMode(telegram.getAuthMode())
                .transportMode(telegram.getTransportMode())
                .webhookSecretToken(telegram.getWebhookSecretToken())
                .conversationScope(telegram.getConversationScope())
                .aggregateIncomingMessages(telegram.getAggregateIncomingMessages())
                .aggregationDelayMs(telegram.getAggregationDelayMs())
                .mergeForwardedMessages(telegram.getMergeForwardedMessages())
                .mergeSequentialFragments(telegram.getMergeSequentialFragments())
                .allowedUsers(copyList(telegram.getAllowedUsers()))
                .inviteCodes(toHostInviteCodes(telegram.getInviteCodes()))
                .build();
    }

    private List<me.golemcore.plugin.api.runtime.model.RuntimeConfig.InviteCode> toPluginInviteCodes(
            List<me.golemcore.bot.domain.model.RuntimeConfig.InviteCode> inviteCodes) {
        if (inviteCodes == null || inviteCodes.isEmpty()) {
            return List.of();
        }
        return inviteCodes.stream()
                .map(this::toPluginInviteCode)
                .toList();
    }

    private List<me.golemcore.bot.domain.model.RuntimeConfig.InviteCode> toHostInviteCodes(
            List<me.golemcore.plugin.api.runtime.model.RuntimeConfig.InviteCode> inviteCodes) {
        if (inviteCodes == null || inviteCodes.isEmpty()) {
            return List.of();
        }
        return inviteCodes.stream()
                .map(inviteCode -> me.golemcore.bot.domain.model.RuntimeConfig.InviteCode.builder()
                        .code(inviteCode.getCode())
                        .used(inviteCode.isUsed())
                        .createdAt(inviteCode.getCreatedAt())
                        .build())
                .toList();
    }

    private me.golemcore.plugin.api.runtime.model.RuntimeConfig.VoiceConfig toPluginVoiceConfig(
            me.golemcore.bot.domain.model.RuntimeConfig.VoiceConfig voice) {
        if (voice == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.RuntimeConfig.VoiceConfig.builder()
                .enabled(voice.getEnabled())
                .apiKey(toPluginSecret(voice.getApiKey()))
                .voiceId(voice.getVoiceId())
                .ttsModelId(voice.getTtsModelId())
                .sttModelId(voice.getSttModelId())
                .speed(voice.getSpeed())
                .telegramRespondWithVoice(voice.getTelegramRespondWithVoice())
                .telegramTranscribeIncoming(voice.getTelegramTranscribeIncoming())
                .sttProvider(voice.getSttProvider())
                .ttsProvider(voice.getTtsProvider())
                .whisperSttUrl(voice.getWhisperSttUrl())
                .whisperSttApiKey(toPluginSecret(voice.getWhisperSttApiKey()))
                .build();
    }

    private me.golemcore.bot.domain.model.RuntimeConfig.VoiceConfig toHostVoiceConfig(
            me.golemcore.plugin.api.runtime.model.RuntimeConfig.VoiceConfig voice) {
        if (voice == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.RuntimeConfig.VoiceConfig.builder()
                .enabled(voice.getEnabled())
                .apiKey(toHostSecret(voice.getApiKey()))
                .voiceId(voice.getVoiceId())
                .ttsModelId(voice.getTtsModelId())
                .sttModelId(voice.getSttModelId())
                .speed(voice.getSpeed())
                .telegramRespondWithVoice(voice.getTelegramRespondWithVoice())
                .telegramTranscribeIncoming(voice.getTelegramTranscribeIncoming())
                .sttProvider(voice.getSttProvider())
                .ttsProvider(voice.getTtsProvider())
                .whisperSttUrl(voice.getWhisperSttUrl())
                .whisperSttApiKey(toHostSecret(voice.getWhisperSttApiKey()))
                .build();
    }

    private me.golemcore.plugin.api.runtime.model.Secret toPluginSecret(me.golemcore.bot.domain.model.Secret secret) {
        if (secret == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.Secret.builder()
                .value(secret.getValue())
                .encrypted(secret.getEncrypted())
                .present(secret.getPresent())
                .build();
    }

    private me.golemcore.bot.domain.model.Secret toHostSecret(me.golemcore.plugin.api.runtime.model.Secret secret) {
        if (secret == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.Secret.builder()
                .value(secret.getValue())
                .encrypted(secret.getEncrypted())
                .present(secret.getPresent())
                .build();
    }

    private Map<String, me.golemcore.plugin.api.runtime.model.UserPreferences.TierOverride> toPluginTierOverrides(
            Map<String, me.golemcore.bot.domain.model.UserPreferences.TierOverride> tierOverrides) {
        if (tierOverrides == null || tierOverrides.isEmpty()) {
            return Map.of();
        }
        Map<String, me.golemcore.plugin.api.runtime.model.UserPreferences.TierOverride> mapped = new LinkedHashMap<>();
        tierOverrides.forEach((key, value) -> mapped.put(
                key,
                value == null ? null
                        : new me.golemcore.plugin.api.runtime.model.UserPreferences.TierOverride(
                                value.getModel(),
                                value.getReasoning())));
        return mapped;
    }

    private Map<String, me.golemcore.bot.domain.model.UserPreferences.TierOverride> toHostTierOverrides(
            Map<String, me.golemcore.plugin.api.runtime.model.UserPreferences.TierOverride> tierOverrides) {
        if (tierOverrides == null || tierOverrides.isEmpty()) {
            return Map.of();
        }
        Map<String, me.golemcore.bot.domain.model.UserPreferences.TierOverride> mapped = new LinkedHashMap<>();
        tierOverrides.forEach((key, value) -> mapped.put(
                key,
                value == null ? null
                        : new me.golemcore.bot.domain.model.UserPreferences.TierOverride(
                                value.getModel(),
                                value.getReasoning())));
        return mapped;
    }

    private me.golemcore.plugin.api.runtime.model.UserPreferences.WebhookConfig toPluginWebhookConfig(
            me.golemcore.bot.domain.model.UserPreferences.WebhookConfig webhooks) {
        if (webhooks == null) {
            return null;
        }
        return me.golemcore.plugin.api.runtime.model.UserPreferences.WebhookConfig.builder()
                .enabled(webhooks.isEnabled())
                .token(toPluginSecret(webhooks.getToken()))
                .maxPayloadSize(webhooks.getMaxPayloadSize())
                .defaultTimeoutSeconds(webhooks.getDefaultTimeoutSeconds())
                .mappings(toPluginHookMappings(webhooks.getMappings()))
                .build();
    }

    private me.golemcore.bot.domain.model.UserPreferences.WebhookConfig toHostWebhookConfig(
            me.golemcore.plugin.api.runtime.model.UserPreferences.WebhookConfig webhooks) {
        if (webhooks == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.UserPreferences.WebhookConfig.builder()
                .enabled(webhooks.isEnabled())
                .token(toHostSecret(webhooks.getToken()))
                .maxPayloadSize(webhooks.getMaxPayloadSize())
                .defaultTimeoutSeconds(webhooks.getDefaultTimeoutSeconds())
                .mappings(toHostHookMappings(webhooks.getMappings()))
                .build();
    }

    private List<me.golemcore.plugin.api.runtime.model.UserPreferences.HookMapping> toPluginHookMappings(
            List<me.golemcore.bot.domain.model.UserPreferences.HookMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<me.golemcore.plugin.api.runtime.model.UserPreferences.HookMapping> mapped = new ArrayList<>(
                mappings.size());
        for (me.golemcore.bot.domain.model.UserPreferences.HookMapping mapping : mappings) {
            if (mapping == null) {
                mapped.add(null);
                continue;
            }
            mapped.add(me.golemcore.plugin.api.runtime.model.UserPreferences.HookMapping.builder()
                    .name(mapping.getName())
                    .action(mapping.getAction())
                    .authMode(mapping.getAuthMode())
                    .hmacHeader(mapping.getHmacHeader())
                    .hmacSecret(toPluginSecret(mapping.getHmacSecret()))
                    .hmacPrefix(mapping.getHmacPrefix())
                    .messageTemplate(mapping.getMessageTemplate())
                    .model(mapping.getModel())
                    .deliver(mapping.isDeliver())
                    .channel(mapping.getChannel())
                    .to(mapping.getTo())
                    .build());
        }
        return mapped;
    }

    private List<me.golemcore.bot.domain.model.UserPreferences.HookMapping> toHostHookMappings(
            List<me.golemcore.plugin.api.runtime.model.UserPreferences.HookMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<me.golemcore.bot.domain.model.UserPreferences.HookMapping> mapped = new ArrayList<>(mappings.size());
        for (me.golemcore.plugin.api.runtime.model.UserPreferences.HookMapping mapping : mappings) {
            if (mapping == null) {
                mapped.add(null);
                continue;
            }
            mapped.add(me.golemcore.bot.domain.model.UserPreferences.HookMapping.builder()
                    .name(mapping.getName())
                    .action(mapping.getAction())
                    .authMode(mapping.getAuthMode())
                    .hmacHeader(mapping.getHmacHeader())
                    .hmacSecret(toHostSecret(mapping.getHmacSecret()))
                    .hmacPrefix(mapping.getHmacPrefix())
                    .messageTemplate(mapping.getMessageTemplate())
                    .model(mapping.getModel())
                    .deliver(mapping.isDeliver())
                    .channel(mapping.getChannel())
                    .to(mapping.getTo())
                    .build());
        }
        return mapped;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(key, copyObject(value)));
        return copied;
    }

    private static List<String> copyList(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static Object copyObject(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copied = new LinkedHashMap<>();
            map.forEach((key, value) -> copied.put(copyObject(key), copyObject(value)));
            return copied;
        }
        if (source instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(item -> copied.add(copyObject(item)));
            return copied;
        }
        if (source instanceof byte[] bytes) {
            return bytes.clone();
        }
        return source;
    }
}
