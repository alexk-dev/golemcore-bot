package me.golemcore.bot.security;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AllowlistValidatorTest {

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String USER_1 = "user1";
    private static final String USER_ANY = "any_user";

    @Mock
    private BotProperties properties;

    @Mock
    private RuntimeConfigService runtimeConfigService;

    private AllowlistValidator validator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new AllowlistValidator(properties, runtimeConfigService);
    }

    // ==================== isAllowed() ====================

    @Test
    void shouldAllowTelegramUserWhenRuntimeAllowlistContainsUser() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(List.of(USER_1, "user2"));

        // Act
        boolean result = validator.isAllowed(CHANNEL_TELEGRAM, "user2");

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldDenyTelegramUserWhenRuntimeAllowlistDoesNotContainUser() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(List.of(USER_1, "user2"));

        // Act
        boolean result = validator.isAllowed(CHANNEL_TELEGRAM, "unknown_user");

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldAllowAllTelegramUsersWhenRuntimeAllowlistIsEmpty() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(Collections.emptyList());

        // Act
        boolean result = validator.isAllowed(CHANNEL_TELEGRAM, USER_ANY);

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldAllowAllTelegramUsersWhenRuntimeAllowlistIsNull() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(null);

        // Act
        boolean result = validator.isAllowed(CHANNEL_TELEGRAM, USER_ANY);

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldDenyUserWhenChannelTypeIsUnknown() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(Collections.emptyList());
        Map<String, BotProperties.ChannelProperties> channels = new HashMap<>();
        when(properties.getChannels()).thenReturn(channels);

        // Act
        boolean result = validator.isAllowed("unknown_channel", USER_1);

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldDenyUserWhenChannelPropertiesIsNull() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(Collections.emptyList());
        Map<String, BotProperties.ChannelProperties> channels = new HashMap<>();
        channels.put("web", null);
        when(properties.getChannels()).thenReturn(channels);

        // Act
        boolean result = validator.isAllowed("web", USER_1);

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldAllowNonTelegramUserWhenChannelExists() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(Collections.emptyList());
        BotProperties.ChannelProperties channelProps = new BotProperties.ChannelProperties();

        Map<String, BotProperties.ChannelProperties> channels = new HashMap<>();
        channels.put("web", channelProps);
        when(properties.getChannels()).thenReturn(channels);

        // Act
        boolean result = validator.isAllowed("web", "user2");

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldAllowNonTelegramAllUsersWhenChannelExists() {
        // Arrange
        when(runtimeConfigService.getTelegramAllowedUserIds()).thenReturn(Collections.emptyList());
        BotProperties.ChannelProperties channelProps = new BotProperties.ChannelProperties();

        Map<String, BotProperties.ChannelProperties> channels = new HashMap<>();
        channels.put("web", channelProps);
        when(properties.getChannels()).thenReturn(channels);

        // Act
        boolean result = validator.isAllowed("web", USER_ANY);

        // Assert
        assertTrue(result);
    }

    // ==================== isBlocked() ====================

    @Test
    void shouldReturnTrueWhenUserIsBlocked() {
        // Arrange
        BotProperties.SecurityProperties securityProps = new BotProperties.SecurityProperties();
        BotProperties.AllowlistProperties allowlistProps = new BotProperties.AllowlistProperties();
        allowlistProps.setBlockedUsers(List.of("blocked_user1", "blocked_user2"));
        securityProps.setAllowlist(allowlistProps);
        when(properties.getSecurity()).thenReturn(securityProps);

        // Act
        boolean result = validator.isBlocked("blocked_user1");

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldReturnFalseWhenUserIsNotBlocked() {
        // Arrange
        BotProperties.SecurityProperties securityProps = new BotProperties.SecurityProperties();
        BotProperties.AllowlistProperties allowlistProps = new BotProperties.AllowlistProperties();
        allowlistProps.setBlockedUsers(List.of("blocked_user1"));
        securityProps.setAllowlist(allowlistProps);
        when(properties.getSecurity()).thenReturn(securityProps);

        // Act
        boolean result = validator.isBlocked("safe_user");

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenBlocklistIsEmpty() {
        // Arrange
        BotProperties.SecurityProperties securityProps = new BotProperties.SecurityProperties();
        BotProperties.AllowlistProperties allowlistProps = new BotProperties.AllowlistProperties();
        allowlistProps.setBlockedUsers(new ArrayList<>());
        securityProps.setAllowlist(allowlistProps);
        when(properties.getSecurity()).thenReturn(securityProps);

        // Act
        boolean result = validator.isBlocked(USER_ANY);

        // Assert
        assertFalse(result);
    }

    @Test
    void shouldReturnFalseWhenBlocklistIsNull() {
        // Arrange
        BotProperties.SecurityProperties securityProps = new BotProperties.SecurityProperties();
        BotProperties.AllowlistProperties allowlistProps = new BotProperties.AllowlistProperties();
        allowlistProps.setBlockedUsers(null);
        securityProps.setAllowlist(allowlistProps);
        when(properties.getSecurity()).thenReturn(securityProps);

        // Act
        boolean result = validator.isBlocked(USER_ANY);

        // Assert
        assertFalse(result);
    }
}
