package me.golemcore.bot.plugin.builtin.webhooks;

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

import me.golemcore.bot.plugin.builtin.webhooks.inbound.WebhookChannelAdapter;
import me.golemcore.bot.plugin.builtin.webhooks.inbound.WebhookController;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.port.inbound.ChannelPort;

/**
 * Built-in plugin for inbound/outbound webhook integrations.
 */
public final class WebhooksPlugin extends AbstractPlugin {

    public WebhooksPlugin() {
        super(
                "webhooks-plugin",
                "Webhooks",
                "Webhook channel adapter and endpoint handlers.",
                "channel:webhooks",
                "endpoint:webhooks");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        WebhookChannelAdapter webhookChannelAdapter = context.requireService(WebhookChannelAdapter.class);
        WebhookController webhookController = context.requireService(WebhookController.class);
        InboundEndpointProvider endpointProvider = new SimpleInboundEndpointProvider("webhooks", webhookController);

        addContribution("channel.webhooks", ChannelPort.class, webhookChannelAdapter);
        addContribution("endpoint.webhooks", InboundEndpointProvider.class, endpointProvider);
    }
}
