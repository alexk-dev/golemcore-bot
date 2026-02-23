package me.golemcore.bot.plugin.api;

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

/**
 * Typed plugin contribution.
 *
 * @param id
 *            stable contribution identifier
 * @param contract
 *            exported contract type
 * @param instance
 *            concrete implementation instance
 * @param <T>
 *            contract generic type
 */
public record PluginContribution<T>(
        String id,
        Class<T> contract,
        T instance
) {
    public static <T> PluginContribution<T> of(String id, Class<T> contract, T instance) {
        return new PluginContribution<>(id, contract, instance);
    }
}
