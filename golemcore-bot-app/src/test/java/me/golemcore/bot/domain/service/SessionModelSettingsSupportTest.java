package me.golemcore.bot.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
