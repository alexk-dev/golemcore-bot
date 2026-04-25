package me.golemcore.bot.domain.loop;

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

import lombok.Builder;
import lombok.Data;

/**
 * Configuration parameters for the agent processing loop. Controls iteration
 * limits, temperature, feature toggles (memory, skills, streaming), and system
 * execution timeouts.
 */
@Data
@Builder
public class AgentLoopConfig {

    @Builder.Default
    private int maxIterations = 20;

    @Builder.Default
    private double temperature = 0.7;

    @Builder.Default
    private boolean enableMemory = true;

    @Builder.Default
    private boolean enableSkills = true;

    @Builder.Default
    private boolean enableStreaming = false;

    @Builder.Default
    private long systemTimeoutMs = 120000;

    public static AgentLoopConfig defaultConfig() {
        return AgentLoopConfig.builder().build();
    }
}
