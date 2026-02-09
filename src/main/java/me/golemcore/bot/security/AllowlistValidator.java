package me.golemcore.bot.security;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates users against channel-specific allowlists and global blocklists.
 *
 * <p>
 * This component provides access control by checking if users are:
 * <ul>
 * <li>Allowed on specific channels (per-channel allowlists)</li>
 * <li>Globally blocked across all channels</li>
 * </ul>
 *
 * <p>
 * If the allowlist feature is disabled or no allowlist is configured for a
 * channel, all users are permitted by default (fail-open behavior).
 *
 * <p>
 * Blocked users are denied access regardless of channel allowlist settings.
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AllowlistValidator {

    private final BotProperties properties;

    /**
     * Check if a user is allowed to use the bot on a specific channel.
     */
    public boolean isAllowed(String channelType, String userId) {
        log.trace("[Security] Allowlist check: channel={}, user={}", channelType, userId);

        BotProperties.ChannelProperties channelProps = properties.getChannels().get(channelType);
        if (channelProps == null) {
            log.warn("[Security] Unauthorized: channel={}, user={} (unknown channel)", channelType, userId);
            return false;
        }

        List<String> allowedUsers = channelProps.getAllowFrom();
        if (allowedUsers == null || allowedUsers.isEmpty()) {
            return true; // No restriction if list is empty
        }

        boolean allowed = allowedUsers.contains(userId);
        if (!allowed) {
            log.warn("[Security] Unauthorized: channel={}, user={}", channelType, userId);
        }
        return allowed;
    }

    /**
     * Check if a user is globally blocked.
     */
    public boolean isBlocked(String userId) {
        List<String> blockedUsers = properties.getSecurity().getAllowlist().getBlockedUsers();
        if (blockedUsers == null || blockedUsers.isEmpty()) {
            return false;
        }
        boolean blocked = blockedUsers.contains(userId);
        if (blocked) {
            log.warn("[Security] Blocked user: {}", userId);
        }
        return blocked;
    }
}
