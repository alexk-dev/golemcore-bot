package me.golemcore.bot.plugin.builtin.browser;

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

import me.golemcore.bot.adapter.outbound.browser.PlaywrightAdapter;
import me.golemcore.bot.domain.component.BrowserComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;
import me.golemcore.bot.port.outbound.BrowserPort;
import me.golemcore.bot.tools.BrowserTool;

/**
 * Built-in plugin for headless browsing.
 */
public final class HeadlessBrowserPlugin extends AbstractPlugin {

    public HeadlessBrowserPlugin() {
        super(
                "headless-browser-plugin",
                "Headless Browser",
                "Headless browser adapter and browse tool.",
                "tool:browse",
                "port:browser",
                "component:browser");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        BrowserTool browserTool = context.requireService(BrowserTool.class);
        PlaywrightAdapter playwrightAdapter = context.requireService(PlaywrightAdapter.class);

        addContribution("tool.browse", ToolComponent.class, browserTool);
        addContribution("port.browser", BrowserPort.class, playwrightAdapter);
        addContribution("component.browser", BrowserComponent.class, playwrightAdapter);
    }
}
