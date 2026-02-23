package me.golemcore.bot.plugin.builtin;

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

import me.golemcore.bot.plugin.api.BotPlugin;
import me.golemcore.bot.plugin.builtin.brave.BraveSearchPlugin;
import me.golemcore.bot.plugin.builtin.browser.HeadlessBrowserPlugin;
import me.golemcore.bot.plugin.builtin.email.EmailPlugin;
import me.golemcore.bot.plugin.builtin.rag.RagHttpPlugin;
import me.golemcore.bot.plugin.builtin.security.SecurityPolicyPlugin;
import me.golemcore.bot.plugin.builtin.telegram.TelegramApiPlugin;
import me.golemcore.bot.plugin.builtin.usage.UsageTrackerPlugin;
import me.golemcore.bot.plugin.builtin.voice.ElevenLabsVoicePlugin;
import me.golemcore.bot.plugin.builtin.webhooks.WebhooksPlugin;
import me.golemcore.bot.plugin.builtin.whisper.WhisperSttPlugin;

import java.util.List;

/**
 * Built-in plugin bundle for the default product distribution.
 */
public final class BuiltinPluginCatalog {

    public List<BotPlugin> createPlugins() {
        return List.of(
                new BraveSearchPlugin(),
                new HeadlessBrowserPlugin(),
                new ElevenLabsVoicePlugin(),
                new WhisperSttPlugin(),
                new RagHttpPlugin(),
                new UsageTrackerPlugin(),
                new EmailPlugin(),
                new SecurityPolicyPlugin(),
                new WebhooksPlugin(),
                new TelegramApiPlugin());
    }
}
