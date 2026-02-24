# Plugins

This document describes the plugin foundation in GolemCore Bot and how plugin-provided settings UI now works end-to-end.

## Scope and Current State

The current foundation provides:

- Stable plugin API (`BotPlugin`, `PluginDescriptor`, `PluginContribution`, `PluginContext`, `BotPluginFactory`)
- Runtime plugin registry with typed contribution lookup
- Built-in plugin bundle for default distribution
- Strict wiring for plugin-managed tools/channels/ports
- Backend-owned declarative settings schemas for plugin UI

Important:

- Built-in plugins are loaded from the in-core catalog at startup.
- API contracts are already aligned for external JAR plugins via `ServiceLoader`.
- Full marketplace/JAR hot-install flow is still a next step.

## Core API

Plugin API package:

- `me.golemcore.bot.plugin.api.BotPlugin`
- `me.golemcore.bot.plugin.api.PluginDescriptor`
- `me.golemcore.bot.plugin.api.PluginContribution`
- `me.golemcore.bot.plugin.api.PluginContext`
- `me.golemcore.bot.plugin.api.BotPluginFactory`

Plugin settings schema API package:

- `me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema`
- `me.golemcore.bot.plugin.api.settings.PluginSettingsFieldSchema`
- `me.golemcore.bot.plugin.api.settings.PluginSettingsFieldOption`
- `me.golemcore.bot.plugin.api.settings.PluginSettingsFieldType`
- `me.golemcore.bot.plugin.api.settings.PluginSettingsSchemas` (helper factory)

## Lifecycle

1. Host creates plugin instance.
2. `start(PluginContext)` is called.
3. Plugin registers typed contributions.
4. Host consumes contributions by contract + stable contribution ID.
5. `stop()` clears/releases plugin resources.

## Descriptor and Capabilities

`PluginDescriptor` fields:

- `id` (stable plugin ID)
- `name` (display name)
- `version` (plugin version)
- `apiVersion` (plugin API compatibility)
- `coreVersionRange` (compatible core versions)
- `description`
- `capabilities` (human-readable capability tags)

Capabilities are metadata tags (not hard-enforced yet). Use namespaced tags, for example:

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

### Plugin-managed ports/components

From `PluginPortResolver`:

- `port.rag` (`RagPort`)
- `port.voice` (`VoicePort`)
- `port.usageTracking` (`UsageTrackingPort`)
- `component.sanitizer` (`SanitizerComponent`)
- `port.confirmation.telegram` (`ConfirmationPort`)

If you replace one of these core-managed integrations, your plugin must provide the exact contribution ID and contract.

## Plugin Settings UI Architecture

Plugin settings UI is now backend-driven.

Flow:

1. Plugin contributes one or more `PluginSettingsSectionSchema` instances.
2. `SettingsController` exposes all schemas via `GET /api/settings/plugins/schemas`.
3. Dashboard loads schemas and renders generic forms dynamically.
4. Save actions update runtime config through existing settings APIs.

### Backend API

- Endpoint: `GET /api/settings/plugins/schemas`
- Controller: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
- Source of truth: `PluginRegistryService.listContributions(PluginSettingsSectionSchema.class)`

### Frontend binding

- API client: `dashboard/src/api/plugins.ts`
- Query hook: `dashboard/src/hooks/usePlugins.ts`
- Generic renderer: `dashboard/src/components/settings/PluginSettingsPanel.tsx`
- Route integration: `dashboard/src/pages/SettingsPage.tsx`

## Declarative Schema Format

`PluginSettingsSectionSchema`:

- `pluginId`: plugin owner ID
- `sectionKey`: settings section key (used by `/settings/:section`)
- `pluginName`: section title in UI
- `description`: section description
- `fields`: list of `PluginSettingsFieldSchema`

`PluginSettingsFieldSchema`:

- `key`: dot-path to runtime config field (example: `tools.browserEnabled`)
- `label`: UI label
- `help`: helper text
- `type`: `switch | text | password | number | select | url`
- `placeholder`: optional input placeholder
- `min`, `max`, `step`: optional numeric constraints
- `options`: select options (`value`, `label`)

### Key rule for `key`

`key` must map to a writable runtime config path. The generic UI reads/writes values by this path.

## Built-in Plugins and UI Sections

Current built-in plugins:

- `brave-search-plugin`
- `headless-browser-plugin`
- `elevenlabs-voice-plugin`
- `whisper-stt-plugin`
- `rag-http-plugin`
- `usage-tracker-plugin`
- `email-plugin`
- `security-policy-plugin`
- `telegram-api-plugin`

Current plugin-driven UI sections:

- `telegram`
- `tool-browser`
- `tool-brave`
- `tool-email`
- `tool-voice`
- `voice-elevenlabs`
- `voice-whisper`
- `usage`
- `rag`
- `advanced-security`

Note: webhooks are currently not plugin-managed and are not part of this schema flow.

## Minimal Plugin Example (with UI schema)

```java
package com.example.plugins.weather;

import java.util.List;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.plugin.api.PluginContext;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSchemas;
import me.golemcore.bot.plugin.api.settings.PluginSettingsSectionSchema;
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

        PluginSettingsSectionSchema settingsSchema = new PluginSettingsSectionSchema(
                "weather-plugin",
                "tool-weather",
                "Weather Plugin",
                "Configuration for weather API integration.",
                List.of(
                        PluginSettingsSchemas.toggle(
                                "weather.enabled",
                                "Enabled",
                                "Enable weather integration."),
                        PluginSettingsSchemas.password(
                                "weather.apiKey",
                                "API Key",
                                "Weather API key.",
                                "Enter API key")));

        addContribution("settings.schema.tool-weather", PluginSettingsSectionSchema.class, settingsSchema);
    }
}
```

## Adding a New Plugin UI Section

1. Define schema in plugin package.

- Create a class like `MyPluginSettingsSchema` returning `PluginSettingsSectionSchema`.
- Keep field keys aligned with runtime config paths.

2. Export schema from plugin.

- In `start()`, add contribution:
- `addContribution("settings.schema.<section>", PluginSettingsSectionSchema.class, MyPluginSettingsSchema.create())`

3. Expose section in dashboard catalog.

- Add section metadata in `dashboard/src/pages/settings/settingsCatalog.ts`.

4. Wire save handling (if needed).

- Generic UI already reads/writes paths.
- For save transport, section key must be handled in `PluginSettingsPanel` save routing (existing runtime settings mutation endpoints).

5. Add tests.

- Backend: controller test for schema endpoint and plugin contribution visibility.
- Frontend: optional renderer tests for field mapping and save behavior.

## External JAR Packaging Contract

For external plugin JARs, use `BotPluginFactory` with Java `ServiceLoader`.

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

- Keep plugin-specific settings and secrets under plugin namespace.
- Fail fast in `start()` if required config is missing.
- Export only stable contribution IDs.

## Recommended Conventions

- Contribution IDs: dot-separated and stable (`tool.x`, `port.x`, `channel.x`, `settings.schema.x`)
- Capabilities: namespaced tags (`tool:*`, `port:*`, `channel:*`, `security:*`, `voice:*`, `rag:*`)
- Keep `start()` idempotent: always `resetContributions()` first
- Keep plugin logic thin: reuse host services where possible

## Testing Utilities

For unit tests, use testing factories:

- `PluginToolCatalog.forTesting(...)`
- `PluginChannelCatalog.forTesting(...)`
- `PluginPortResolver.forTesting(...)`

This keeps production beans with a single DI constructor while preserving lightweight tests.
