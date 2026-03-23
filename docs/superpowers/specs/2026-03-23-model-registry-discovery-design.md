# Model Registry Discovery Design

Date: 2026-03-23
Area: `golemcore-bot` model catalog and dashboard
Status: Approved in interactive design review

## Summary

Model Catalog should be able to enrich a user-selected discovered model with default capability settings from a separate remote repository, `golemcore-models`, without making that repository part of the runtime catalog itself.

The new design adds a dedicated `ModelRegistryService` with a configurable remote source and a local cache. The registry is consulted only when the user chooses a specific model from `Discovery from API` and wants to create a catalog entry from it. The live discovery list remains sourced only from the provider API.

Registry lookup is hybrid:

- first try a provider-specific config,
- then fall back to a shared model-id config.

Registry files contain a full model capability document, but intentionally omit `provider` so the same config can be reused across multiple providers.

## Goals

- Keep `Discovery from API` backed by the provider API only.
- Load default model settings from `golemcore-models` only when the user selects a discovered model to create a catalog entry.
- Reuse the existing runtime-config editing pattern used by marketplace source configuration.
- Cache registry lookups locally for one day.
- Fall back to stale cache entries when the remote source is temporarily unavailable.
- Support both provider-specific and shared model defaults without introducing a central manifest.

## Non-Goals

- Turning `golemcore-models` into a full marketplace or browsable catalog inside the dashboard.
- Auto-populating or syncing `models/models.json` from the registry in bulk.
- Enriching the discovery list itself with registry data before model selection.
- Supporting multiple source types in v1. The source remains a single remote repository URL plus branch.
- Making cache TTL user-configurable in v1.

## Current Context

Today the dashboard flow is:

1. `AvailableModelInsertModal` calls `GET /api/models/discover/{provider}`.
2. The backend returns discovered models from the provider API.
3. The UI builds a draft locally through `createDraftFromSuggestion(...)`.
4. If no saved model exists yet, the draft uses hardcoded UI defaults such as `supportsVision = true`, `supportsTemperature = true`, and `maxInputTokens = 128000`.

This means discovered models can be inserted quickly, but they do not pick up curated defaults from an external registry.

## Proposed Repository Layout

`golemcore-models` is a plain repository of JSON files.

Shared defaults:

- `models/<model-id>.json`

Provider-specific overrides:

- `providers/<provider>/<model-id>.json`

The `<model-id>` path preserves slash-separated namespaces from the raw model id. Examples:

- shared `gpt-5.1` -> `models/gpt-5.1.json`
- shared `openai/gpt-4o` -> `models/openai/gpt-4o.json`
- provider-specific `provider=openrouter`, `modelId=openai/gpt-4o` -> `providers/openrouter/openai/gpt-4o.json`

Lookup order:

1. `providers/<provider>/<model-id>.json`
2. `models/<model-id>.json`

The first successful match wins.

## Registry File Format

Each registry file contains the full capability payload needed to prefill a model draft, but without `provider`.

Expected JSON shape:

```json
{
  "displayName": "GPT-5.1",
  "supportsVision": true,
  "supportsTemperature": false,
  "maxInputTokens": 1000000,
  "reasoning": {
    "default": "medium",
    "levels": {
      "low": { "maxInputTokens": 1000000 },
      "medium": { "maxInputTokens": 1000000 },
      "high": { "maxInputTokens": 500000 }
    }
  }
}
```

Important implementation constraint:

- Do not deserialize registry files directly into the existing `ModelConfigService.ModelSettings`.
- That class currently has a default `provider` value, so missing `provider` in registry JSON would silently become `openai`.
- Introduce a dedicated registry DTO, for example `RegistryModelSettings`, with the same fields except `provider`.
- When returning resolved defaults to the UI, inject the requested `provider` into a normal `ModelSettings` response object.

## Runtime Configuration

Add a new top-level runtime config section:

- `modelRegistry.repositoryUrl`
- `modelRegistry.branch`

Recommended behavior:

- blank `repositoryUrl` means the registry is disabled,
- blank `branch` defaults to `main`,
- settings are read and saved through the existing runtime settings API, not a separate registry settings endpoint.

This keeps the source configuration aligned with the existing settings model used for other configurable remote integrations.

## Service Architecture

Add a dedicated backend layer, `ModelRegistryService`.

Responsibilities:

- read the configured source from runtime config,
- resolve raw file URLs from repository URL + branch,
- fetch provider-specific or shared config JSON on demand,
- maintain a local cache for both hits and misses,
- return a resolved `ModelSettings` response with the selected provider injected.

Explicit non-responsibilities:

- no bulk synchronization into `models/models.json`,
- no modification of saved model catalog entries by itself,
- no enrichment of the discovery list endpoint,
- no browsing or listing of remote registry contents.

`ProviderModelDiscoveryService` remains responsible only for live provider discovery.

## Cache Design

The local registry cache is entry-based and on-demand. There is no repository-wide snapshot step in v1.

Recommended cache root:

- `cache/model-registry/<source-hash>/`

Where `<source-hash>` is derived from `repositoryUrl + "#" + branch`.

Each resolved candidate path stores metadata about the last lookup. This should include both positive and negative cache entries so repeated misses do not keep hitting the remote source.

Recommended metadata fields:

- `relativePath`
- `status`: `hit` or `miss`
- `cachedAt`
- `sourceType`: `provider` or `shared`

For `hit` entries, the cached JSON payload is stored alongside the metadata.

TTL rules:

- fresh if `cachedAt` is less than 24 hours old,
- stale if `cachedAt` is 24 hours old or more.

Fresh entries are returned without remote access.

When an entry is stale or missing:

1. try remote provider-specific path,
2. if not found, try remote shared path,
3. update cache from the remote result,
4. if remote lookup fails and a stale cache entry exists, use stale cache,
5. if remote lookup fails and no cache entry exists, return no defaults.

Caching misses matters for two cases:

- models that have no provider-specific file but do have a shared fallback,
- models with no registry config at all.

## Remote Fetching Strategy

The registry is resolved by direct raw-file fetches, not by cloning the repository and not by downloading a full tree.

For each selection attempt, the service only needs to check at most two remote files:

1. provider-specific candidate
2. shared fallback candidate

This keeps the remote integration narrow and inexpensive.

The remote repository URL should follow the same GitHub-style assumption already used elsewhere in the codebase for marketplace-like remote sources.

## API Design

Keep the existing discovery endpoint unchanged:

- `GET /api/models/discover/{provider}`

Add a new resolve endpoint used only when the user selects a discovered model:

- `POST /api/models/registry/resolve`

Request:

```json
{
  "provider": "openai",
  "modelId": "gpt-5.1"
}
```

Response:

```json
{
  "defaultSettings": {
    "provider": "openai",
    "displayName": "GPT-5.1",
    "supportsVision": true,
    "supportsTemperature": false,
    "maxInputTokens": 1000000,
    "reasoning": {
      "default": "medium",
      "levels": {
        "low": { "maxInputTokens": 1000000 },
        "medium": { "maxInputTokens": 1000000 },
        "high": { "maxInputTokens": 500000 }
      }
    }
  },
  "configSource": "provider",
  "cacheStatus": "remote-hit"
}
```

Response contract:

- `defaultSettings` is `null` when no config is available,
- `configSource` is `provider`, `shared`, or `null`,
- `cacheStatus` is one of:
  - `fresh-hit`
  - `stale-hit`
  - `remote-hit`
  - `miss`

Error behavior:

- validation errors such as blank `provider` or `modelId` return `400`,
- expected registry miss or source unavailability should still return `200` with `defaultSettings: null` or stale fallback data,
- only unexpected server failures should return `5xx`.

This keeps model creation non-blocking even when the registry source is unavailable.

## Dashboard UX

Add a small source configuration card to `Model Catalog` that follows the existing marketplace configuration style, but only for one remote source shape.

Editable fields:

- repository URL
- branch

Suggested behavior:

- the card saves through the existing runtime config flow,
- no separate `Reload registry` button in v1,
- the card explains that defaults are fetched on demand when a discovered model is selected,
- if the source is not configured, discovery still works and falls back to current UI defaults.

Update the discovery modal behavior:

- the discovered list remains unchanged and lightweight,
- when the user clicks a model, the UI calls the new resolve endpoint,
- while resolve is in flight, the selected item enters a loading state and repeat clicks are disabled,
- after resolve returns, the UI creates a draft from registry defaults if present,
- if resolve returns `defaultSettings: null`, the current default draft behavior remains unchanged.

Optional UI hints that are useful but not required for v1:

- a small badge or toast when defaults came from the registry,
- a note when stale cache was used.

## Model Catalog Draft Construction

The draft creation path should change from purely local construction to resolved-default-aware construction.

Recommended flow:

1. user selects a discovered model in `AvailableModelInsertModal`,
2. modal calls `resolve` for that model,
3. if `defaultSettings` exists, build the draft from those resolved settings,
4. preserve the existing duplicate-id behavior that scopes to `provider/modelId` when necessary,
5. if no defaults are resolved, reuse the current local fallback defaults.

This avoids pushing registry logic into the discovery list and keeps the selection action as the enrichment boundary.

## Data Flow

1. The operator configures `modelRegistry.repositoryUrl` and `modelRegistry.branch` in `Model Catalog`.
2. The operator opens `Discovery from API` and selects a provider.
3. The UI loads discovered models from the provider API as it does today.
4. The operator clicks one discovered model.
5. The UI sends `provider + modelId` to `POST /api/models/registry/resolve`.
6. `ModelRegistryService` checks provider-specific cache and shared cache.
7. If no fresh cache entry exists, the service performs up to two raw-file remote lookups.
8. The service returns resolved defaults or `null`.
9. The UI builds the model draft and opens it in the editor.

## Failure Handling and Degradation

- Provider discovery failure remains a discovery error and is unrelated to the registry.
- Missing registry source configuration is not fatal. The resolve endpoint returns no defaults and the UI uses existing local defaults.
- If remote lookup fails but a stale cached hit exists, return the stale cached settings.
- If remote lookup fails and only a stale cached miss exists, treat the result as a miss.
- If a provider-specific config is missing but a shared config exists, the shared config should resolve without repeated provider-specific remote fetches while the miss cache is fresh.
- If a cached or remote JSON payload is invalid, do not use it as a successful hit. If a stale valid cache entry exists for the same path, prefer the stale entry.

The main product rule is that a user should still be able to create a model entry even when the registry integration is unavailable.

## Testing Strategy

Backend unit coverage:

- provider-specific hit beats shared hit,
- shared fallback works when provider-specific config is absent,
- miss is cached,
- stale cache triggers remote refresh,
- stale hit is used when remote lookup fails,
- stale miss does not masquerade as a hit,
- registry DTO parsing rejects invalid payloads,
- resolved response injects the requested provider into returned `ModelSettings`.

Backend controller coverage:

- `resolve` returns `400` for invalid input,
- `resolve` returns `200` with `defaultSettings` on provider hit,
- `resolve` returns `200` with `defaultSettings` on shared hit,
- `resolve` returns `200` with `defaultSettings: null` on miss.

Dashboard coverage:

- source config card reads and saves `repositoryUrl` and `branch`,
- selecting a discovered model triggers resolve before draft creation,
- resolved defaults populate the draft,
- null resolve response falls back to current defaults,
- selection UI handles in-flight state cleanly.

## Implementation Notes for Planning

- Keep `GET /api/models/discover/{provider}` unchanged to avoid mixing live discovery with registry resolution.
- Add a dedicated registry DTO instead of reusing the existing persisted `ModelSettings` class for remote parsing.
- Treat cache storage as internal implementation detail; only the resolved draft contract matters to the UI.
- Reuse existing GitHub raw URL helpers or extraction patterns from marketplace services where practical, but do not import marketplace-specific domain concepts into the model registry.
- Prefer a small, focused UI change inside the existing Model Catalog tab instead of introducing a new settings page.

## Open Questions

None for this iteration.

Approved constraints:

- `golemcore-models` is only a repository of JSON configs,
- lookup is hybrid: provider-specific first, shared second,
- registry files omit `provider`,
- source is configured in the dashboard like marketplace,
- cache TTL is one day,
- stale cached hits may be used when the remote source is unavailable.
