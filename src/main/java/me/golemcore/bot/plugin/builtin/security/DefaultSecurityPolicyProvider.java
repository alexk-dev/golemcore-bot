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

import me.golemcore.bot.domain.component.SanitizerComponent;
import me.golemcore.bot.security.AllowlistValidator;
import me.golemcore.bot.security.InjectionGuard;

import java.util.List;

final class DefaultSecurityPolicyProvider implements SecurityPolicyProvider {

    private final SanitizerComponent sanitizerComponent;
    private final AllowlistValidator allowlistValidator;
    private final InjectionGuard injectionGuard;

    DefaultSecurityPolicyProvider(SanitizerComponent sanitizerComponent,
            AllowlistValidator allowlistValidator,
            InjectionGuard injectionGuard) {
        this.sanitizerComponent = sanitizerComponent;
        this.allowlistValidator = allowlistValidator;
        this.injectionGuard = injectionGuard;
    }

    @Override
    public SanitizerComponent.SanitizationResult checkInput(String input) {
        return sanitizerComponent.check(input);
    }

    @Override
    public List<String> detectThreats(String input) {
        return injectionGuard.detectAllThreats(input);
    }

    @Override
    public boolean isChannelUserAllowed(String channelType, String userId) {
        return allowlistValidator.isAllowed(channelType, userId);
    }
}
