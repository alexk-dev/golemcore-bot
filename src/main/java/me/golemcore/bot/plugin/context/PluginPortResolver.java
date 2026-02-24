package me.golemcore.bot.plugin.context;

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

import me.golemcore.bot.domain.component.SanitizerComponent;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import me.golemcore.bot.port.outbound.CorePortResolver;
import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.bot.port.outbound.VoicePort;
import org.springframework.stereotype.Component;

/**
 * Strict resolver for plugin-managed core ports/components.
 */
@Component
public class PluginPortResolver implements CorePortResolver {

    private static final String CONTRIBUTION_RAG = "port.rag";
    private static final String CONTRIBUTION_VOICE = "port.voice";
    private static final String CONTRIBUTION_SANITIZER = "component.sanitizer";
    private static final String CONTRIBUTION_CONFIRMATION = "port.confirmation.telegram";

    private final PluginRegistryService pluginRegistryService;

    public PluginPortResolver(PluginRegistryService pluginRegistryService) {
        this.pluginRegistryService = pluginRegistryService;
    }

    public static PluginPortResolver forTesting(RagPort ragPort, VoicePort voicePort,
            SanitizerComponent sanitizerComponent,
            ConfirmationPort confirmationPort) {
        return new PluginPortResolver(null) {
            @Override
            public RagPort requireRagPort() {
                return requireOverridePort(ragPort, CONTRIBUTION_RAG, RagPort.class);
            }

            @Override
            public VoicePort requireVoicePort() {
                return requireOverridePort(voicePort, CONTRIBUTION_VOICE, VoicePort.class);
            }

            @Override
            public SanitizerComponent requireSanitizerComponent() {
                return requireOverridePort(sanitizerComponent, CONTRIBUTION_SANITIZER, SanitizerComponent.class);
            }

            @Override
            public ConfirmationPort requireConfirmationPort() {
                return requireOverridePort(confirmationPort, CONTRIBUTION_CONFIRMATION, ConfirmationPort.class);
            }
        };
    }

    @Override
    public RagPort requireRagPort() {
        return requireContribution(CONTRIBUTION_RAG, RagPort.class);
    }

    @Override
    public VoicePort requireVoicePort() {
        return requireContribution(CONTRIBUTION_VOICE, VoicePort.class);
    }

    @Override
    public SanitizerComponent requireSanitizerComponent() {
        return requireContribution(CONTRIBUTION_SANITIZER, SanitizerComponent.class);
    }

    @Override
    public ConfirmationPort requireConfirmationPort() {
        return requireContribution(CONTRIBUTION_CONFIRMATION, ConfirmationPort.class);
    }

    private <T> T requireContribution(String contributionId, Class<T> contract) {
        if (pluginRegistryService == null) {
            throw new IllegalStateException("No plugin registry available for contribution: " + contributionId);
        }
        return pluginRegistryService.requireContribution(contributionId, contract);
    }

    private static <T> T requireOverridePort(T overridePort, String contributionId, Class<T> contract) {
        if (overridePort == null) {
            throw new IllegalStateException("No test override available for contribution: "
                    + contributionId + " (" + contract.getName() + ")");
        }
        return overridePort;
    }
}
