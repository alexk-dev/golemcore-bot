package me.golemcore.bot.plugin.builtin.email;

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

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.plugin.builtin.email.tool.ImapTool;
import me.golemcore.bot.plugin.builtin.email.tool.SmtpTool;

/**
 * Built-in plugin for email operations (IMAP + SMTP).
 */
public final class EmailPlugin extends AbstractPlugin {

    public EmailPlugin() {
        super(
                "email-plugin",
                "Email",
                "Email integration tools for IMAP receive and SMTP send.",
                "tool:imap",
                "tool:smtp");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        ImapTool imapTool = context.requireService(ImapTool.class);
        SmtpTool smtpTool = context.requireService(SmtpTool.class);

        addContribution("tool.imap", ToolComponent.class, imapTool);
        addContribution("tool.smtp", ToolComponent.class, smtpTool);
        addContribution("settings.schema.tool-email", PluginSettingsSectionSchema.class,
                EmailPluginSettingsSchema.create());
    }
}
