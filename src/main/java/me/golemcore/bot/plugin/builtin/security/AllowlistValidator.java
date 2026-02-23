package me.golemcore.bot.plugin.builtin.security;

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

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates users against channel-specific allowlists. For Telegram, access is
 * controlled only by RuntimeConfig allowlist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AllowlistValidator {

    private static final String CHANNEL_TELEGRAM = "telegram";

    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;

    /**
     * Check if a user is allowed to use the bot on a specific channel.
     */
    public boolean isAllowed(String channelType, String userId) {
        log.trace("[Security] Allowlist check: channel={}, user={}", channelType, userId);

        if (!runtimeConfigService.isAllowlistEnabled()) {
            return true;
        }

        // Telegram allowlist is RuntimeConfig-only (no properties fallback)
        if (CHANNEL_TELEGRAM.equals(channelType)) {
            List<String> runtimeAllowed = runtimeConfigService.getTelegramAllowedUsers();
            boolean allowed = runtimeAllowed != null && runtimeAllowed.contains(userId);
            if (!allowed) {
                log.warn("[Security] Unauthorized: channel={}, user={}", channelType, userId);
            }
            return allowed;
        }

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

}
