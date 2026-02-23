package me.golemcore.bot.plugin.builtin.usage;

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

import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import me.golemcore.bot.usage.LlmUsageTrackerImpl;

/**
 * Built-in plugin for LLM usage tracking.
 */
public final class UsageTrackerPlugin extends AbstractPlugin {

    public UsageTrackerPlugin() {
        super(
                "usage-tracker-plugin",
                "Usage Tracker",
                "Tracks token, cost and latency usage metrics for LLM calls.",
                "port:usage-tracking",
                "metrics:llm");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        LlmUsageTrackerImpl usageTracker = context.requireService(LlmUsageTrackerImpl.class);
        addContribution("port.usageTracking", UsageTrackingPort.class, usageTracker);
    }
}
