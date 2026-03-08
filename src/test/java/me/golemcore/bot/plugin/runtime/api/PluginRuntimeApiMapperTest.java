package me.golemcore.bot.plugin.runtime.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRuntimeApiMapperTest {

    private final PluginRuntimeApiMapper mapper = new PluginRuntimeApiMapper();

    @Test
    void shouldMapRuntimeConfigSubsetExplicitly() {
        me.golemcore.bot.domain.model.RuntimeConfig runtimeConfig = me.golemcore.bot.domain.model.RuntimeConfig
                .builder()
                .telegram(me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig.builder()
                        .enabled(true)
                        .token(me.golemcore.bot.domain.model.Secret.of("token"))
                        .authMode("invite_only")
                        .allowedUsers(List.of("alice"))
                        .inviteCodes(List.of(me.golemcore.bot.domain.model.RuntimeConfig.InviteCode.builder()
                                .code("invite-1")
                                .used(false)
                                .createdAt(Instant.parse("2026-03-06T12:00:00Z"))
                                .build()))
                        .build())
                .voice(me.golemcore.bot.domain.model.RuntimeConfig.VoiceConfig.builder()
                        .enabled(true)
                        .apiKey(me.golemcore.bot.domain.model.Secret.of("voice-key"))
                        .voiceId("voice-1")
                        .ttsModelId("tts-1")
                        .sttModelId("stt-1")
                        .speed(1.25f)
                        .telegramRespondWithVoice(true)
                        .telegramTranscribeIncoming(true)
                        .sttProvider("golemcore/whisper")
                        .ttsProvider("golemcore/elevenlabs")
                        .whisperSttUrl("http://whisper")
                        .whisperSttApiKey(me.golemcore.bot.domain.model.Secret.of("whisper-key"))
                        .build())
                .build();

        me.golemcore.plugin.api.runtime.model.RuntimeConfig pluginConfig = mapper.toPluginRuntimeConfig(runtimeConfig);
        assertEquals("token", pluginConfig.getTelegram().getToken().getValue());
        assertEquals("invite-1", pluginConfig.getTelegram().getInviteCodes().getFirst().getCode());
        assertEquals("golemcore/whisper", pluginConfig.getVoice().getSttProvider());

        me.golemcore.bot.domain.model.RuntimeConfig mappedBack = mapper.toHostRuntimeConfig(pluginConfig);
        assertEquals("voice-1", mappedBack.getVoice().getVoiceId());
        assertEquals("alice", mappedBack.getTelegram().getAllowedUsers().getFirst());
        assertEquals("whisper-key", mappedBack.getVoice().getWhisperSttApiKey().getValue());
    }

    @Test
    void shouldMapPlanAndUserPreferencesExplicitly() {
        Map<String, Object> toolArguments = new LinkedHashMap<>();
        toolArguments.put("url", "https://example.com");
        toolArguments.put("headers", new ArrayList<>(List.of("x-trace-id")));

        me.golemcore.bot.domain.model.Plan plan = me.golemcore.bot.domain.model.Plan.builder()
                .id("plan-1")
                .title("My plan")
                .description("desc")
                .markdown("# Plan")
                .status(me.golemcore.bot.domain.model.Plan.PlanStatus.READY)
                .steps(List.of(me.golemcore.bot.domain.model.PlanStep.builder()
                        .id("step-1")
                        .planId("plan-1")
                        .toolName("browser")
                        .description("open")
                        .toolArguments(toolArguments)
                        .order(1)
                        .status(me.golemcore.bot.domain.model.PlanStep.StepStatus.PENDING)
                        .createdAt(Instant.parse("2026-03-06T12:00:00Z"))
                        .build()))
                .channelType("telegram")
                .chatId("chat-1")
                .transportChatId("transport-1")
                .createdAt(Instant.parse("2026-03-06T12:00:00Z"))
                .updatedAt(Instant.parse("2026-03-06T12:01:00Z"))
                .build();

        me.golemcore.plugin.api.runtime.model.Plan pluginPlan = mapper.toPluginPlan(plan);
        assertEquals("plan-1", pluginPlan.getId());
        assertEquals(me.golemcore.plugin.api.runtime.model.Plan.PlanStatus.READY, pluginPlan.getStatus());
        assertEquals("https://example.com", pluginPlan.getSteps().getFirst().getToolArguments().get("url"));
        assertEquals("x-trace-id",
                ((List<?>) pluginPlan.getSteps().getFirst().getToolArguments().get("headers")).getFirst());
        assertNotSame(toolArguments, pluginPlan.getSteps().getFirst().getToolArguments());
        assertNotSame(toolArguments.get("headers"), pluginPlan.getSteps().getFirst().getToolArguments().get("headers"));

        me.golemcore.bot.domain.model.UserPreferences preferences = me.golemcore.bot.domain.model.UserPreferences
                .builder()
                .language("ru")
                .notificationsEnabled(false)
                .timezone("Europe/Moscow")
                .modelTier("smart")
                .tierForce(true)
                .tierOverrides(Map.of("coding",
                        new me.golemcore.bot.domain.model.UserPreferences.TierOverride("openai/gpt-5.2", "high")))
                .webhooks(me.golemcore.bot.domain.model.UserPreferences.WebhookConfig.builder()
                        .enabled(true)
                        .token(me.golemcore.bot.domain.model.Secret.of("webhook-token"))
                        .maxPayloadSize(777)
                        .defaultTimeoutSeconds(33)
                        .mappings(List.of(me.golemcore.bot.domain.model.UserPreferences.HookMapping.builder()
                                .name("github")
                                .action("agent")
                                .authMode("hmac")
                                .hmacHeader("x-signature")
                                .hmacSecret(me.golemcore.bot.domain.model.Secret.of("hmac"))
                                .hmacPrefix("sha256=")
                                .messageTemplate("{repository.full_name}")
                                .model("smart")
                                .deliver(true)
                                .channel("telegram")
                                .to("42")
                                .build()))
                        .build())
                .build();

        me.golemcore.plugin.api.runtime.model.UserPreferences pluginPreferences = mapper
                .toPluginUserPreferences(preferences);
        assertEquals("ru", pluginPreferences.getLanguage());
        assertEquals("openai/gpt-5.2", pluginPreferences.getTierOverrides().get("coding").getModel());
        assertEquals("github", pluginPreferences.getWebhooks().getMappings().getFirst().getName());

        me.golemcore.bot.domain.model.UserPreferences mappedBack = mapper.toHostUserPreferences(pluginPreferences);
        assertEquals("Europe/Moscow", mappedBack.getTimezone());
        assertNotNull(mappedBack.getWebhooks().getToken());
        assertEquals("telegram", mappedBack.getWebhooks().getMappings().getFirst().getChannel());
    }

    @Test
    void shouldHandleNullInputsAndNullCollectionFields() {
        assertNull(mapper.toHostSessionIdentity(null));
        assertNull(mapper.toPluginPlan(null));
        assertNull(mapper.toPluginRuntimeConfig(null));
        assertNull(mapper.toHostRuntimeConfig(null));
        assertNull(mapper.toPluginUserPreferences(null));
        assertNull(mapper.toHostUserPreferences(null));
        assertNull(mapper.toPluginInviteCode(null));

        me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig telegram = me.golemcore.bot.domain.model.RuntimeConfig.TelegramConfig
                .builder()
                .build();
        telegram.setAllowedUsers(null);
        telegram.setInviteCodes(null);
        me.golemcore.bot.domain.model.RuntimeConfig runtimeConfig = me.golemcore.bot.domain.model.RuntimeConfig
                .builder()
                .telegram(telegram)
                .voice(me.golemcore.bot.domain.model.RuntimeConfig.VoiceConfig.builder().build())
                .build();

        me.golemcore.plugin.api.runtime.model.RuntimeConfig pluginConfig = mapper.toPluginRuntimeConfig(runtimeConfig);
        assertNull(pluginConfig.getTelegram().getAllowedUsers());
        assertTrue(pluginConfig.getTelegram().getInviteCodes().isEmpty());

        me.golemcore.bot.domain.model.RuntimeConfig mappedBack = mapper.toHostRuntimeConfig(pluginConfig);
        assertNull(mappedBack.getTelegram().getAllowedUsers());
        assertTrue(mappedBack.getTelegram().getInviteCodes().isEmpty());

        me.golemcore.bot.domain.model.SessionIdentity hostSessionIdentity = mapper.toHostSessionIdentity(
                new me.golemcore.plugin.api.runtime.model.SessionIdentity("telegram", "chat-1"));
        assertEquals("telegram", hostSessionIdentity.channelType());
        assertEquals("chat-1", hostSessionIdentity.conversationKey());
    }

    @Test
    void shouldPreserveNullEntriesInMappedCollections() {
        List<me.golemcore.bot.domain.model.PlanStep> planSteps = new ArrayList<>();
        planSteps.add(null);
        planSteps.add(me.golemcore.bot.domain.model.PlanStep.builder()
                .id("step-1")
                .description("open")
                .build());

        me.golemcore.bot.domain.model.Plan plan = me.golemcore.bot.domain.model.Plan.builder()
                .id("plan-1")
                .steps(planSteps)
                .build();
        plan.setStatus(null);

        me.golemcore.plugin.api.runtime.model.Plan pluginPlan = mapper.toPluginPlan(plan);
        assertNull(pluginPlan.getStatus());
        assertNull(pluginPlan.getSteps().getFirst());
        assertEquals("step-1", pluginPlan.getSteps().get(1).getId());

        List<me.golemcore.bot.domain.model.UserPreferences.HookMapping> mappings = new ArrayList<>();
        mappings.add(null);
        mappings.add(me.golemcore.bot.domain.model.UserPreferences.HookMapping.builder()
                .name("github")
                .channel("telegram")
                .build());
        Map<String, me.golemcore.bot.domain.model.UserPreferences.TierOverride> tierOverrides = new LinkedHashMap<>();
        tierOverrides.put("coding", null);

        me.golemcore.bot.domain.model.UserPreferences preferences = me.golemcore.bot.domain.model.UserPreferences
                .builder()
                .tierOverrides(tierOverrides)
                .webhooks(me.golemcore.bot.domain.model.UserPreferences.WebhookConfig.builder()
                        .mappings(mappings)
                        .build())
                .build();

        me.golemcore.plugin.api.runtime.model.UserPreferences pluginPreferences = mapper
                .toPluginUserPreferences(preferences);
        assertNull(pluginPreferences.getTierOverrides().get("coding"));
        assertNull(pluginPreferences.getWebhooks().getMappings().getFirst());
        assertEquals("github", pluginPreferences.getWebhooks().getMappings().get(1).getName());

        me.golemcore.bot.domain.model.UserPreferences mappedBack = mapper.toHostUserPreferences(pluginPreferences);
        assertNull(mappedBack.getTierOverrides().get("coding"));
        assertNull(mappedBack.getWebhooks().getMappings().getFirst());
        assertEquals("telegram", mappedBack.getWebhooks().getMappings().get(1).getChannel());
    }
}
