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

final class SimpleInboundEndpointProvider implements InboundEndpointProvider {

    private final String providerId;
    private final Object endpointHandler;

    SimpleInboundEndpointProvider(String providerId, Object endpointHandler) {
        this.providerId = providerId;
        this.endpointHandler = endpointHandler;
    }

    @Override
    public String getProviderId() {
        return providerId;
    }

    @Override
    public Object getEndpointHandler() {
        return endpointHandler;
    }
}
