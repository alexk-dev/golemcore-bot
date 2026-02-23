# Plugins

This document describes the current plugin foundation in GolemCore Bot and how to create new plugins.

## Scope and Current State

The current foundation provides:

- A stable plugin API (`BotPlugin`, `PluginDescriptor`, `PluginContribution`, `PluginContext`, `BotPluginFactory`)
- Runtime plugin registry and typed contribution lookup
- Built-in plugin bundle for default distribution
- Strict wiring for plugin-managed tools/channels/ports

Important: at this stage, plugins are loaded from the built-in catalog in-core. The API is already designed for external JAR plugins and `ServiceLoader`, but full marketplace/JAR hot-install flow is a next step.

## Core API

Plugin API package:

- `me.golemcore.bot.plugin.api.BotPlugin`
- `me.golemcore.bot.plugin.api.PluginDescriptor`
- `me.golemcore.bot.plugin.api.PluginContribution`
- `me.golemcore.bot.plugin.api.PluginContext`
- `me.golemcore.bot.plugin.api.BotPluginFactory`

### Lifecycle

1. Host creates plugin instance
2. `start(PluginContext)` is called
3. Plugin registers typed contributions
4. Host consumes contributions by contract + stable contribution ID
5. `stop()` clears/releases plugin resources

## Descriptor and Capabilities

`PluginDescriptor` fields:

- `id` (stable plugin ID)
- `name` (display name)
- `version` (plugin version)
- `apiVersion` (plugin API compatibility)
- `coreVersionRange` (compatible core versions)
- `description`
- `capabilities` (human-readable capability tags)

Capabilities are metadata tags (not hard-enforced by runtime yet). Use a predictable namespace, for example:

- `tool:brave_search`
- `port:rag`
- `channel:telegram`
- `security:sanitization`

## Contribution Model

Each contribution has:

- `id` (stable runtime ID)
- `contract` (`Class<T>`)
- `instance` (implementation)

Runtime registry is strict for required integrations: missing required contribution IDs fail fast.

## Mandatory IDs Used by Core (Current)

### Plugin-managed tools

From `PluginToolCatalog`:

- `tool.brave_search` -> tool name `brave_search`
- `tool.browse` -> tool name `browse`
- `tool.send_voice` -> tool name `send_voice`
- `tool.imap` -> tool name `imap`
- `tool.smtp` -> tool name `smtp`

### Plugin-managed channels

From `PluginChannelCatalog`:

- `channel.telegram` -> channel type `telegram`
- `channel.webhooks` -> channel type `webhook`

### Plugin-managed ports/components

From `PluginPortResolver`:

- `port.rag` (`RagPort`)
- `port.voice` (`VoicePort`)
- `port.usageTracking` (`UsageTrackingPort`)
- `component.sanitizer` (`SanitizerComponent`)
- `port.confirmation.telegram` (`ConfirmationPort`)

If you replace one of these core-managed integrations, your plugin must provide the exact contribution ID and contract.

## Built-in Plugin Capability Catalog

Default bundled plugins currently include:

- `brave-search-plugin`: `tool:brave_search`, `search:web`
- `headless-browser-plugin`: `tool:browse`, `port:browser`, `component:browser`
- `elevenlabs-voice-plugin`: `port:voice`, `tool:send_voice`, `voice:elevenlabs`
- `whisper-stt-plugin`: `stt:whisper`
- `rag-http-plugin`: `port:rag`, `rag:lightrag`
- `usage-tracker-plugin`: `port:usage-tracking`, `metrics:llm`
- `email-plugin`: `tool:imap`, `tool:smtp`
- `security-policy-plugin`: `security:sanitization`, `security:allowlist`
- `webhooks-plugin`: `channel:webhooks`, `endpoint:webhooks`
- `telegram-api-plugin`: `channel:telegram`, `port:confirmation`

## Minimal Plugin Example

```java
package com.example.plugins.weather;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.builtin.AbstractPlugin;

public final class WeatherPlugin extends AbstractPlugin {

    public WeatherPlugin() {
        super(
                "weather-plugin",
                "Weather",
                "Weather tool integration.",
                "tool:weather",
                "external:weather-api");
    }

    @Override
    public void start(PluginContext context) {
        resetContributions();
        ToolComponent weatherTool = context.requireService(WeatherTool.class);
        addContribution("tool.weather", ToolComponent.class, weatherTool);
    }
}
```

## External JAR Packaging Contract

For external plugin JARs, use `BotPluginFactory` with Java `ServiceLoader`:

1. Implement factory:

```java
package com.example.plugins.weather;

import me.golemcore.bot.plugin.api.BotPlugin;
import me.golemcore.bot.plugin.api.BotPluginFactory;

public final class WeatherPluginFactory implements BotPluginFactory {
    @Override
    public BotPlugin create() {
        return new WeatherPlugin();
    }
}
```

2. Add service file:

`META-INF/services/me.golemcore.bot.plugin.api.BotPluginFactory`

with one line:

`com.example.plugins.weather.WeatherPluginFactory`

3. Build JAR.

## PluginContext Usage

`PluginContext` gives plugins controlled host access:

- `requireService(Class<T>)`: resolve host beans
- `pluginConfig(pluginId)`: plugin config section
- `secret(key)`: secret lookup abstraction
- `pluginDataDir(pluginId)`: plugin-local storage path

Recommended pattern:

- Keep plugin-specific settings and secrets under a plugin namespace
- Fail fast in `start()` if required config is missing
- Export only stable contribution IDs

## Recommended Conventions

- Contribution IDs: dot-separated and stable (`tool.x`, `port.x`, `channel.x`)
- Capabilities: namespaced tags (`tool:*`, `port:*`, `channel:*`, `security:*`, `voice:*`, `rag:*`)
- Keep `start()` idempotent: always `resetContributions()` first
- Keep plugin logic thin: reuse host services where possible

## Testing

For unit tests, use testing factories:

- `PluginToolCatalog.forTesting(...)`
- `PluginChannelCatalog.forTesting(...)`
- `PluginPortResolver.forTesting(...)`

This keeps production beans with a single DI constructor while preserving lightweight tests.
