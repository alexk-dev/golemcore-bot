package me.golemcore.bot.port.outbound;

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

import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Applies startup-managed self-evolving runtime config overrides without
 * coupling runtime config ownership to the self-evolving implementation module.
 */
public interface SelfEvolvingBootstrapOverridePort {

    void apply(RuntimeConfig runtimeConfig);

    void restorePersistedValues(RuntimeConfig candidateConfig, RuntimeConfig persistedConfig);

    boolean hasManagedOverrides();

    List<String> getOverriddenPaths();
}
