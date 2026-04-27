package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionModelSettingsSupportTest {

    @ParameterizedTest
    @ValueSource(strings = { "telegram", "web", "judge-plan" })
    void shouldAllowModelSettingsInheritanceForRegularChannels(String channelType) {
        assertTrue(SessionModelSettingsSupport.shouldInheritModelSettings(channelType));
    }

    @ParameterizedTest
    @ValueSource(strings = { "hive", "webhook", "judge", "judge_outcome", "judge_process", "judge_tiebreaker" })
    void shouldDisableModelSettingsInheritanceForExcludedChannels(String channelType) {
        assertFalse(SessionModelSettingsSupport.shouldInheritModelSettings(channelType));
    }

    @Test
    void shouldTrimChannelTypeBeforeCheckingInheritance() {
        assertTrue(SessionModelSettingsSupport.shouldInheritModelSettings(" Telegram "));
        assertFalse(SessionModelSettingsSupport.shouldInheritModelSettings(" WEBHOOK "));
        assertFalse(SessionModelSettingsSupport.shouldInheritModelSettings(" judge_review "));
        assertFalse(SessionModelSettingsSupport.shouldInheritModelSettings(null));
        assertFalse(SessionModelSettingsSupport.shouldInheritModelSettings(" "));
    }

    @Test
    void shouldDetectModelSettingsWhenTierOrForceFlagIsPresent() {
        assertFalse(SessionModelSettingsSupport.hasModelSettings(null));
        assertFalse(SessionModelSettingsSupport.hasModelSettings(AgentSession.builder().metadata(null).build()));
        assertFalse(
                SessionModelSettingsSupport.hasModelSettings(AgentSession.builder().metadata(new HashMap<>()).build()));

        AgentSession tierOnly = AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER, "coding"))).build();
        AgentSession forceOnly = AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER_FORCE, true))).build();

        assertTrue(SessionModelSettingsSupport.hasModelSettings(tierOnly));
        assertTrue(SessionModelSettingsSupport.hasModelSettings(forceOnly));
    }

    @Test
    void shouldReadTrimmedModelTierOnlyWhenStoredAsText() {
        assertNull(SessionModelSettingsSupport.readModelTier(null));
        assertNull(SessionModelSettingsSupport.readModelTier(AgentSession.builder().metadata(null).build()));
        assertNull(SessionModelSettingsSupport.readModelTier(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER, 42))).build()));
        assertNull(SessionModelSettingsSupport.readModelTier(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER, " "))).build()));

        AgentSession session = AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER, " coding "))).build();

        assertEquals("coding", SessionModelSettingsSupport.readModelTier(session));
    }

    @Test
    void shouldReadForceFlagFromBooleanOrText() {
        assertFalse(SessionModelSettingsSupport.readForce(null));
        assertFalse(SessionModelSettingsSupport.readForce(AgentSession.builder().metadata(null).build()));
        assertFalse(SessionModelSettingsSupport.readForce(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER_FORCE, " "))).build()));
        assertFalse(SessionModelSettingsSupport.readForce(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER_FORCE, 1))).build()));

        assertTrue(SessionModelSettingsSupport.readForce(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER_FORCE, true))).build()));
        assertTrue(SessionModelSettingsSupport.readForce(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER_FORCE, " true "))).build()));
        assertFalse(SessionModelSettingsSupport.readForce(AgentSession.builder()
                .metadata(new HashMap<>(Map.of(ContextAttributes.SESSION_MODEL_TIER_FORCE, "false"))).build()));
    }

    @Test
    void shouldWriteTrimmedModelSettingsAndRemoveBlankTier() {
        AgentSession session = AgentSession.builder().metadata(null).build();

        SessionModelSettingsSupport.writeModelSettings(session, " coding ", true);

        assertEquals("coding", session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER));
        assertEquals(true, session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE));

        SessionModelSettingsSupport.writeModelSettings(session, " ", false);

        assertFalse(session.getMetadata().containsKey(ContextAttributes.SESSION_MODEL_TIER));
        assertEquals(false, session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE));
    }

    @Test
    void shouldIgnoreNullSessionWhenWritingModelSettings() {
        SessionModelSettingsSupport.writeModelSettings(null, "coding", true);
    }

    @Test
    void shouldInheritModelSettingsOnlyIntoUnsetTarget() {
        AgentSession source = AgentSession.builder().metadata(new HashMap<>(
                Map.of(ContextAttributes.SESSION_MODEL_TIER, "deep", ContextAttributes.SESSION_MODEL_TIER_FORCE, true)))
                .build();
        AgentSession target = AgentSession.builder().metadata(null).build();

        SessionModelSettingsSupport.inheritModelSettings(source, target);

        assertEquals("deep", target.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER));
        assertEquals(true, target.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE));

        target.getMetadata().put(ContextAttributes.SESSION_MODEL_TIER, "balanced");
        SessionModelSettingsSupport.inheritModelSettings(source, target);

        assertEquals("balanced", target.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER));
    }

    @Test
    void shouldSkipInheritanceWhenSourceOrTargetIsMissingOrSourceHasNoSettings() {
        AgentSession sourceWithoutSettings = AgentSession.builder().metadata(new HashMap<>()).build();
        AgentSession target = AgentSession.builder().metadata(new HashMap<>()).build();

        SessionModelSettingsSupport.inheritModelSettings(null, target);
        SessionModelSettingsSupport.inheritModelSettings(sourceWithoutSettings, null);
        SessionModelSettingsSupport.inheritModelSettings(sourceWithoutSettings, target);

        assertFalse(SessionModelSettingsSupport.hasModelSettings(target));
    }
}
