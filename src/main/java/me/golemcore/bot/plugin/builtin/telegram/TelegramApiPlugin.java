package me.golemcore.bot.plugin.builtin.telegram;

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

import me.golemcore.bot.adapter.inbound.telegram.TelegramAdapter;
import me.golemcore.bot.adapter.outbound.confirmation.TelegramConfirmationAdapter;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;

/**
 * Built-in plugin for Telegram channel/API integrations.
 */
public final class TelegramApiPlugin extends AbstractPlugin {

    public TelegramApiPlugin() {
        super(
                "telegram-api-plugin",
                "Telegram API",
                "Telegram inbound channel and confirmation adapter.",
                "channel:telegram",
                "port:confirmation");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        TelegramAdapter telegramAdapter = context.requireService(TelegramAdapter.class);
        TelegramConfirmationAdapter telegramConfirmationAdapter = context
                .requireService(TelegramConfirmationAdapter.class);

        addContribution("channel.telegram", ChannelPort.class, telegramAdapter);
        addContribution("port.confirmation.telegram", ConfirmationPort.class, telegramConfirmationAdapter);
    }
}
