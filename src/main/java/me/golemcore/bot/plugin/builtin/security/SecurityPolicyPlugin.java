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
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.security.AllowlistValidator;
import me.golemcore.bot.security.DefaultSanitizerComponent;
import me.golemcore.bot.security.InjectionGuard;

/**
 * Built-in plugin for security policies (sanitization, threat detection and
 * allowlist checks).
 */
public final class SecurityPolicyPlugin extends AbstractPlugin {

    public SecurityPolicyPlugin() {
        super(
                "security-policy-plugin",
                "Security Policy",
                "Security extension with sanitization and allowlist policy checks.",
                "security:sanitization",
                "security:allowlist");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        DefaultSanitizerComponent sanitizerComponent = context.requireService(DefaultSanitizerComponent.class);
        AllowlistValidator allowlistValidator = context.requireService(AllowlistValidator.class);
        InjectionGuard injectionGuard = context.requireService(InjectionGuard.class);
        SecurityPolicyProvider securityPolicyProvider = new DefaultSecurityPolicyProvider(
                sanitizerComponent, allowlistValidator, injectionGuard);

        addContribution("security.policy", SecurityPolicyProvider.class, securityPolicyProvider);
        addContribution("component.sanitizer", SanitizerComponent.class, sanitizerComponent);
    }
}
