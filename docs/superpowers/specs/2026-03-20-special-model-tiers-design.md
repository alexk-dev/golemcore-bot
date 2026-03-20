# Special Model Tiers Design

## Goal

Add optional `special1` through `special5` model tiers that can be configured in the same UI and runtime config as existing tiers, can be referenced explicitly by skills, reflection, plans, goals, tasks, webhook requests, and manual tier selection, but are never selected by implicit automatic routing unless explicitly requested.

## Required Outcomes

- Support `special1`, `special2`, `special3`, `special4`, and `special5` as valid tier ids everywhere explicit tier selection is already supported.
- Keep implicit model routing limited to the normal routing flow and never auto-pick a `special*` tier.
- Return a user-visible error when an explicit tier is referenced but not configured or points to an invalid model.
- Replace scattered hardcoded tier lists with one canonical tier catalog in backend and one mirrored canonical catalog in dashboard.
- Preserve a fixed display and iteration order:
  1. `balanced`
  2. `smart`
  3. `deep`
  4. `coding`
  5. `special1`
  6. `special2`
  7. `special3`
  8. `special4`
  9. `special5`

## Current Problems

### 1. Tier definitions are duplicated

Tier ids are currently hardcoded in multiple places:

- `CommandRouter`
- `TierTool`
- `ChatWindow`
- `AutoModeTab`
- `ModelsTab`
- skill editor constants
- assorted validation paths

This creates drift risk as soon as new tier ids are introduced.

### 2. Unknown tiers silently fall back

`ModelSelectionService.resolveFromRouter()` currently falls through to `balanced` for any unknown tier. That is unsafe for `special*` because a typo or missing config would silently run on the wrong model.

### 3. Model router config is tied to fixed fields

`RuntimeConfig.ModelRouterConfig` currently uses flat per-tier fields:

- `balancedModel`
- `smartModel`
- `codingModel`
- `deepModel`
- and reasoning siblings

That scales poorly and keeps tier knowledge spread across many switch statements.

## Design

## 1. Canonical Tier Catalog

Introduce a shared concept of tier metadata in backend and dashboard.

### Backend

Add a dedicated support class, for example:

- `me.golemcore.bot.domain.model.ModelTierCatalog`

This class owns:

- all valid tier ids
- fixed order
- labels
- whether a tier is eligible for implicit automatic routing
- whether a tier is selectable in UI and commands
- whether a tier is the special routing slot

Recommended shape:

```java
public final class ModelTierCatalog {

    public static final String ROUTING = "routing";

    public static final List<String> ORDERED_EXPLICIT_TIERS = List.of(
            "balanced",
            "smart",
            "deep",
            "coding",
            "special1",
            "special2",
            "special3",
            "special4",
            "special5");

    public static final Set<String> IMPLICIT_ROUTING_TIERS = Set.of(
            "balanced",
            "smart",
            "deep",
            "coding");

    public static boolean isKnownTier(String tier) { ... }
    public static boolean isExplicitSelectableTier(String tier) { ... }
    public static boolean isImplicitRoutingTier(String tier) { ... }
    public static List<String> orderedExplicitTiers() { ... }
}
```

### Dashboard

Add a mirrored typed catalog in one place, for example:

- `dashboard/src/features/models/modelTierCatalog.ts`

This file should export:

- ordered ids
- labels
- optional badge/display metadata
- helpers for `isKnownTier`, `isImplicitTier`

Every UI selector should consume this catalog instead of local arrays.

## 2. Canonical Model Router Structure

Replace flat router fields with canonical bindings.

### Current

```java
String balancedModel;
String balancedModelReasoning;
String smartModel;
String smartModelReasoning;
...
```

### Proposed

```java
public static class ModelRouterConfig {
    private Double temperature;
    private TierBinding routing;
    private LinkedHashMap<String, TierBinding> tiers;
    private Boolean dynamicTierEnabled;
}

public static class TierBinding {
    private String model;
    private String reasoning;
}
```

Rules:

- `routing` remains separate because it is a router slot, not a conversation tier.
- `tiers` contains explicit bindings for `balanced/smart/deep/coding/special1..special5`.
- keys must be validated against `ModelTierCatalog.ORDERED_EXPLICIT_TIERS`.
- persistence order must follow the fixed tier order.

## 3. Backward-Compatible Migration

Existing installs already persist the flat structure in `preferences/model-router.json`.

Required migration behavior:

- on read:
  - accept legacy flat structure
  - hydrate canonical `routing` + `tiers`
- on write:
  - write only canonical structure

This keeps existing installs working after upgrade without manual migration.

Migration mapping:

- `routingModel` + `routingModelReasoning` -> `routing`
- `balancedModel` + `balancedModelReasoning` -> `tiers["balanced"]`
- `smartModel` + `smartModelReasoning` -> `tiers["smart"]`
- `deepModel` + `deepModelReasoning` -> `tiers["deep"]`
- `codingModel` + `codingModelReasoning` -> `tiers["coding"]`
- `special1Model` style legacy fields do not exist yet and do not need backward support

## 4. Resolution Semantics

Split tier resolution into two explicit modes.

### Implicit routing mode

Used for normal conversations when no explicit tier was requested by:

- user force tier
- active skill
- active reflection config
- plan mode tier
- goal/task reflection tier
- webhook override

Implicit routing can only resolve among:

- `balanced`
- `smart`
- `deep`
- `coding`

`special*` tiers are never selected here.

### Explicit routing mode

Used when tier comes from:

- `/tier`
- `set_tier`
- skill `model_tier`
- skill `reflection_tier`
- plan `modelTier`
- auto mode `modelTier`
- goal/task `reflectionModelTier`
- webhook `modelTier`
- stored user preference tier

Explicit routing accepts all catalog tiers:

- `balanced`
- `smart`
- `deep`
- `coding`
- `special1..special5`

### Error semantics

If explicit tier is invalid or not bound, fail hard.

Examples:

- `Unknown tier 'special9'`
- `Tier 'special3' is not configured`
- `Tier 'special2' points to unknown model 'openai/missing-model'`
- `Tier 'special4' uses provider 'anthropic' which is not configured`

No fallback to `balanced` is allowed for explicit requests.

## 5. ModelSelectionService Changes

`ModelSelectionService` should stop using tier-specific switch logic.

Recommended API split:

- `ModelSelection resolveForExplicitTier(String tier)`
- `ModelSelection resolveForImplicitTier(String tier)`
- `ModelSelection resolveForContext(AgentContext context)`

Implementation rules:

- resolve bindings from canonical `modelRouter.routing` and `modelRouter.tiers`
- validate tier id against `ModelTierCatalog`
- validate model existence against `ModelConfigService`
- validate provider availability against runtime-configured providers
- auto-fill reasoning defaults only after the model binding has been proven valid

## 6. Context Resolution Rules

The existing precedence rules stay intact, but tier ids now come from the catalog.

Priority remains:

1. forced user tier
2. reflection-specific tier when reflection is active
3. active skill tier
4. plan tier
5. user preference tier
6. implicit default flow

Important distinction:

- if any of these sources explicitly yields `special*`, that is valid
- if no explicit source chooses `special*`, implicit flow must not produce it

## 7. Dynamic Tier System

`DynamicTierSystem` must continue to auto-upgrade only to `coding`.

It must not:

- auto-pick `special*`
- consider `special*` as part of automatic escalation logic

The current behavior remains valid, but tier checks should use the catalog helper instead of raw string sets.

## 8. Commands and Tools

### `/tier`

`CommandRouter` should:

- accept all explicit selectable tiers from the catalog
- display them in fixed order
- stop using a local hardcoded set

### `/model`

`/model list` should display:

- `balanced`
- `smart`
- `deep`
- `coding`
- `special1`
- `special2`
- `special3`
- `special4`
- `special5`

in exactly that order.

The `routing` slot should continue to be listed separately.

### `set_tier` tool

`TierTool` should:

- accept all explicit selectable tiers
- advertise them in schema enum in fixed order
- preserve the current user-lock behavior

## 9. Dashboard Surfaces

All tier selectors must use the shared catalog and the fixed order.

Affected areas include:

- model router settings tab
- chat tier selector / toolbar
- auto mode settings
- plan mode selector
- goals/task reflection selectors
- webhook tier selectors
- skill editor `model_tier`
- skill editor `reflection_tier` when added or already exposed

### Models tab

`ModelsTab` should render cards from the ordered catalog instead of a local four-item array.

Required order:

- Routing
- Balanced
- Smart
- Deep
- Coding
- Special 1
- Special 2
- Special 3
- Special 4
- Special 5

### UX copy

Special tiers should be labeled as optional explicit tiers, not automatic routing tiers.

For example:

- `Special 1`
- `Special 2`

Optional help text:

> Special tiers are only used when selected explicitly by user settings, skills, plans, reflection, or other feature-specific routing.

## 10. Provider Safety and Validation

Provider deletion safety in `SettingsController` must inspect:

- routing slot
- every configured explicit tier including `special1..special5`

Otherwise a provider could be deleted while still referenced by a special tier.

Runtime validation must cover:

- tier key is known
- model exists in models config
- model provider is configured
- reasoning level is valid for the chosen model

## 11. Sessions and Observability

Session metadata can continue storing plain string tier ids, including `special*`.

No schema change is required for:

- message metadata
- outgoing response metadata
- usage tracking labels

But UI renderers that display tier badges or labels must treat `special*` as known tiers.

## 12. Testing Requirements

### Backend

- migration from legacy flat `model-router.json` to canonical map structure
- known-tier validation for `special1..special5`
- explicit special tier resolution succeeds when configured
- explicit special tier fails when missing model binding
- explicit special tier fails when model is unknown
- explicit special tier fails when provider is unavailable
- implicit routing never resolves to `special*`
- `/tier` accepts specials
- `/model list` preserves fixed order
- `set_tier` tool schema and validation include specials
- provider deletion blocked when used by any special tier

### Dashboard

- shared tier catalog exports fixed order
- models tab renders specials in fixed order
- skill editor model tier selector includes specials
- auto mode and other tier selectors include specials
- unknown tier normalization no longer silently collapses valid `special*`

## 13. Non-Goals

- No automatic heuristic selection among `special*`
- No free-form arbitrary tier ids beyond `special1..special5`
- No attempt to infer special-tier purpose from model metadata
- No fallback-to-balanced behavior for explicit invalid tiers

## Recommended Implementation Sequence

1. Introduce canonical backend tier catalog.
2. Migrate `ModelRouterConfig` to canonical binding structure with backward-compatible read path.
3. Refactor `ModelSelectionService` to explicit vs implicit resolution APIs.
4. Update command/tool validation and listing logic to consume the catalog.
5. Add dashboard shared tier catalog and migrate UI selectors.
6. Add validation and provider safety coverage for special tiers.
7. Add regression tests for migration, ordering, and explicit error behavior.

