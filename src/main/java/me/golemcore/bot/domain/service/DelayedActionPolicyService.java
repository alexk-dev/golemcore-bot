package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Runtime policy for delayed action scheduling and proactive delivery.
 */
@Service
public class DelayedActionPolicyService {

    private static final String CHANNEL_WEBHOOK = "webhook";

    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService userPreferencesService;
    private final ChannelRuntimePort channelRuntimePort;

    public DelayedActionPolicyService(RuntimeConfigService runtimeConfigService,
            UserPreferencesService userPreferencesService,
            ChannelRuntimePort channelRuntimePort) {
        this.runtimeConfigService = runtimeConfigService;
        this.userPreferencesService = userPreferencesService;
        this.channelRuntimePort = channelRuntimePort;
    }

    public boolean canScheduleActions() {
        return runtimeConfigService.isDelayedActionsEnabled();
    }

    public boolean canScheduleActions(String channelType) {
        return canPersistDelayedIntent(channelType);
    }

    public boolean canPersistDelayedIntent(String channelType) {
        return canScheduleActions() && isChannelSupported(channelType);
    }

    public boolean canWakeSessionLater(String channelType, String transportChatId) {
        return canPersistDelayedIntent(channelType)
                && runtimeConfigService.isDelayedActionsRunLaterEnabled();
    }

    public boolean canScheduleRunLater(String channelType, String transportChatId) {
        return canWakeSessionLater(channelType, transportChatId);
    }

    public boolean notificationsEnabled() {
        return userPreferencesService.getPreferences().isNotificationsEnabled();
    }

    public boolean isChannelSupported(String channelType) {
        if (StringValueSupport.isBlank(channelType)) {
            return false;
        }
        return !CHANNEL_WEBHOOK.equals(channelType.trim().toLowerCase(Locale.ROOT));
    }

    public boolean supportsProactiveMessage(String channelType, String transportChatId) {
        if (!canPersistDelayedIntent(channelType) || !notificationsEnabled()) {
            return false;
        }
        ChannelDeliveryPort channel = findChannel(channelType);
        if (channel == null) {
            return false;
        }
        return channel.supportsProactiveMessage(transportChatId);
    }

    public boolean supportsDelayedExecution(String channelType, String transportChatId) {
        return canWakeSessionLater(channelType, transportChatId);
    }

    public boolean supportsProactiveDocument(String channelType, String transportChatId) {
        if (!supportsProactiveMessage(channelType, transportChatId)) {
            return false;
        }
        ChannelDeliveryPort channel = findChannel(channelType);
        if (channel == null) {
            return false;
        }
        return channel.supportsProactiveDocument(transportChatId);
    }

    private ChannelDeliveryPort findChannel(String channelType) {
        if (StringValueSupport.isBlank(channelType)) {
            return null;
        }
        return channelRuntimePort.findChannel(channelType).orElse(null);
    }
}
