# Model Catalog Discovery Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-demand model registry resolution to Model Catalog so a discovered provider model can be created with curated default settings from `golemcore-models`, using provider-first lookup, a one-day cache TTL, and stale-cache fallback.

**Architecture:** Keep live discovery unchanged in `ProviderModelDiscoveryService`, then add a separate `ModelRegistryService` that resolves defaults only when a user selects a discovered model. Persist registry source settings as a new runtime-config section, expose a small resolve endpoint under `/api/models`, and update the dashboard to save the source and use resolved settings before draft creation.

**Tech Stack:** Spring Boot 4, Java 17, Jackson, `StoragePort`, React 18, TypeScript, React Query, Vitest, Maven Surefire.

---

## File Map

### Backend runtime-config contract

- Modify: `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
  - Add `ModelRegistryConfig` and register its config section.
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
  - Load, normalize, and persist the new runtime-config section.
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
  - Merge and validate `modelRegistry` updates in the existing runtime-config flow.
- Test: `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java`

### Backend model registry resolution

- Create: `src/main/java/me/golemcore/bot/domain/service/ModelRegistryService.java`
  - Implement GitHub-style raw-file lookup, cache TTL, stale fallback, and DTO mapping.
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/ModelsController.java`
  - Add `POST /api/models/registry/resolve`.
- Test: `src/test/java/me/golemcore/bot/domain/service/ModelRegistryServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/ModelsControllerTest.java`

### Dashboard runtime-config and resolve API plumbing

- Modify: `dashboard/src/api/settings.ts`
  - Add `ModelRegistryConfig` to the runtime-config type and serialization helpers.
- Modify: `dashboard/src/hooks/useSettings.ts`
  - Add a generic runtime-config update mutation for Model Catalog.
- Modify: `dashboard/src/api/models.ts`
  - Add resolve request/response types and API call.
- Modify: `dashboard/src/hooks/useModels.ts`
  - Add a resolve mutation hook.

### Dashboard Model Catalog UI

- Modify: `dashboard/src/pages/SettingsPage.tsx`
  - Pass runtime-config context needed by Model Catalog.
- Modify: `dashboard/src/pages/settings/ModelCatalogTab.tsx`
  - Render a source card and wire save state.
- Create: `dashboard/src/pages/settings/models/ModelRegistrySourceCard.tsx`
  - Isolate registry source form UI so `ModelCatalogTab.tsx` stays small.
- Modify: `dashboard/src/pages/settings/models/AvailableModelInsertModal.tsx`
  - Resolve defaults when a model is selected and expose pending state.
- Modify: `dashboard/src/pages/settings/models/ModelCatalogEditor.tsx`
  - Await resolved settings before building the draft.
- Modify: `dashboard/src/pages/settings/models/modelCatalogTypes.ts`
  - Add draft helpers for resolved settings.
- Test: `dashboard/src/pages/settings/ModelCatalogTab.test.tsx`
- Test: `dashboard/src/pages/settings/models/modelCatalogTypes.test.ts`

### Documentation

- Modify: `docs/CONFIGURATION.md`
- Modify: `docs/DASHBOARD.md`
- Modify: `docs/MODEL_ROUTING.md`

## Task 1: Add Runtime Config Support For Model Registry

**Files:**
- Modify: `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java`

- [ ] **Step 1: Write failing runtime-config tests for the new section**

Add tests in `RuntimeConfigServiceTest` that prove:

- default config exposes a non-null `modelRegistry`,
- `model-registry.json` loads from persisted section storage,
- persisting `RuntimeConfig` writes `model-registry.json`,
- blank branch is normalized to `main`.

Suggested test shape:

```java
@Test
void shouldPersistModelRegistrySection() {
    RuntimeConfig config = service.getRuntimeConfig();
    config.setModelRegistry(RuntimeConfig.ModelRegistryConfig.builder()
            .repositoryUrl("https://github.com/alexk-dev/golemcore-models")
            .branch("main")
            .build());

    service.updateRuntimeConfig(config);

    assertTrue(persistedSections.containsKey("model-registry.json"));
}
```

- [ ] **Step 2: Run the targeted runtime-config test class**

Run: `./mvnw -Dtest=RuntimeConfigServiceTest test`

Expected: FAIL because `RuntimeConfig` does not yet expose `modelRegistry`.

- [ ] **Step 3: Add the `RuntimeConfig.ModelRegistryConfig` type**

Extend `RuntimeConfig.java` with:

```java
@Builder.Default
private ModelRegistryConfig modelRegistry = new ModelRegistryConfig();

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public static class ModelRegistryConfig {
    private String repositoryUrl;
    @Builder.Default
    private String branch = "main";
}
```

Also add `MODEL_REGISTRY("model-registry", ModelRegistryConfig.class)` to `ConfigSection`.

- [ ] **Step 4: Wire load, persist, and normalize in `RuntimeConfigService`**

Update:

- `persist(...)` to write `MODEL_REGISTRY`,
- `loadOrCreate()` to load and attach `modelRegistry`,
- `normalizeRuntimeConfig(...)` to ensure non-null section and `branch = "main"` when blank.

Keep the new section inside the same per-file preference model as the other runtime-config sections.

- [ ] **Step 5: Re-run the targeted runtime-config test class**

Run: `./mvnw -Dtest=RuntimeConfigServiceTest test`

Expected: PASS with the new section covered.

- [ ] **Step 6: Commit the runtime-config contract**

```bash
git add src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java \
  src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java \
  src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java
git commit -m "feat(model-catalog): add model registry runtime config"
```

## Task 2: Merge And Validate Model Registry Settings Through SettingsController

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java`

- [ ] **Step 1: Add failing controller tests for merge and validation**

Cover:

- `updateRuntimeConfig(...)` preserves existing `modelRegistry` when the incoming section is empty,
- explicit `repositoryUrl` and `branch` are saved,
- invalid non-http(s) `repositoryUrl` is rejected with `IllegalArgumentException`.

- [ ] **Step 2: Run the targeted controller tests**

Run: `./mvnw -Dtest=SettingsControllerTest test`

Expected: FAIL because the controller does not yet know about `modelRegistry`.

- [ ] **Step 3: Extend runtime-config merging to include `modelRegistry`**

Update `mergeRuntimeConfigSections(...)` to include:

```java
.modelRegistry(mergeSection(
        patch.getModelRegistry(),
        baseline.getModelRegistry(),
        RuntimeConfig.ModelRegistryConfig::new))
```

- [ ] **Step 4: Add `modelRegistry` normalization and validation**

Add a small validator in `SettingsController` that:

- accepts blank `repositoryUrl`,
- requires `http` or `https` when `repositoryUrl` is present,
- trims blank `branch` back to `main`.

Use the same style as the existing URL validators.

- [ ] **Step 5: Re-run the targeted controller tests**

Run: `./mvnw -Dtest=SettingsControllerTest test`

Expected: PASS with the new controller behavior covered.

- [ ] **Step 6: Commit the controller wiring**

```bash
git add src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java
git commit -m "feat(model-catalog): validate model registry settings"
```

## Task 3: Build ModelRegistryService With Cache TTL And Stale Fallback

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/service/ModelRegistryService.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/ModelRegistryServiceTest.java`

- [ ] **Step 1: Write failing service tests for lookup order and cache behavior**

Cover at least:

- provider-specific remote hit beats shared remote hit,
- shared fallback is used after a provider-specific miss,
- cache hit younger than one day skips remote fetch,
- stale cache triggers remote refresh,
- stale cached hit is used when remote fetch fails,
- stale cached miss stays a miss when remote fetch fails,
- invalid registry JSON does not become a successful hit.

Model the remote layer with overridable protected methods or a tiny injected collaborator so tests do not use real network I/O.

- [ ] **Step 2: Run only the new service test class**

Run: `./mvnw -Dtest=ModelRegistryServiceTest test`

Expected: FAIL because the service does not exist yet.

- [ ] **Step 3: Create the registry DTOs and service contract**

Implement `ModelRegistryService` with focused nested records or small inner classes for:

- `RegistryModelSettings`
- `ResolveResult`
- `CacheStatus`
- cached entry metadata

Expose a public method like:

```java
public ResolveResult resolveDefaults(String provider, String modelId)
```

The result should carry:

- resolved `ModelConfigService.ModelSettings` or `null`,
- `configSource` as `provider`, `shared`, or `null`,
- `cacheStatus` as `fresh-hit`, `stale-hit`, `remote-hit`, or `miss`.

- [ ] **Step 4: Implement cache storage and TTL**

Use `StoragePort` under a dedicated directory such as:

- directory: `cache`
- path prefix: `model-registry/<source-hash>/...`

Persist both hit and miss metadata with `cachedAt`, and treat entries older than 24 hours as stale.

- [ ] **Step 5: Implement GitHub-style raw-file resolution**

Implement two candidate paths per request:

1. `providers/<provider>/<model-id>.json`
2. `models/<model-id>.json`

Parse the configured repository URL into `<owner>/<repo>`, then build raw URLs against `https://raw.githubusercontent.com`.

Keep this logic local to `ModelRegistryService`; do not pull marketplace abstractions into the model catalog path.

- [ ] **Step 6: Inject the selected provider into returned settings**

Do not deserialize remote JSON directly into `ModelConfigService.ModelSettings`.

Instead:

1. parse into `RegistryModelSettings` without `provider`,
2. copy into a new `ModelConfigService.ModelSettings`,
3. set `provider` from the request.

- [ ] **Step 7: Re-run the targeted service test class**

Run: `./mvnw -Dtest=ModelRegistryServiceTest test`

Expected: PASS with no real network access.

- [ ] **Step 8: Commit the registry service**

```bash
git add src/main/java/me/golemcore/bot/domain/service/ModelRegistryService.java \
  src/test/java/me/golemcore/bot/domain/service/ModelRegistryServiceTest.java
git commit -m "feat(model-catalog): add model registry resolver"
```

## Task 4: Expose The Resolve Endpoint In ModelsController

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/ModelsController.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/ModelsControllerTest.java`

- [ ] **Step 1: Add failing controller tests for the resolve endpoint**

Cover:

- success on provider-specific hit,
- success on shared hit,
- `defaultSettings: null` on miss,
- `400` for blank `provider` or `modelId`.

- [ ] **Step 2: Run the targeted controller tests**

Run: `./mvnw -Dtest=ModelsControllerTest test`

Expected: FAIL because `/api/models/registry/resolve` does not exist.

- [ ] **Step 3: Inject `ModelRegistryService` into `ModelsController`**

Update the constructor-injected dependencies and add request/response records such as:

```java
private record ResolveRegistryRequest(String provider, String modelId) {
}

private record ResolveRegistryResponse(
        ModelConfigService.ModelSettings defaultSettings,
        String configSource,
        String cacheStatus) {
}
```

- [ ] **Step 4: Add `POST /api/models/registry/resolve`**

The endpoint should:

- reject blank `provider` or `modelId` with `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`,
- call `modelRegistryService.resolveDefaults(...)`,
- return `200` even for misses and remote-unavailable fallback cases.

Keep `GET /api/models/discover/{provider}` unchanged.

- [ ] **Step 5: Re-run the targeted controller tests**

Run: `./mvnw -Dtest=ModelsControllerTest test`

Expected: PASS with the new response contract covered.

- [ ] **Step 6: Commit the resolve endpoint**

```bash
git add src/main/java/me/golemcore/bot/adapter/inbound/web/controller/ModelsController.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/ModelsControllerTest.java
git commit -m "feat(model-catalog): add model registry resolve endpoint"
```

## Task 5: Add Dashboard Runtime-Config And Resolve API Plumbing

**Files:**
- Modify: `dashboard/src/api/settings.ts`
- Modify: `dashboard/src/hooks/useSettings.ts`
- Modify: `dashboard/src/api/models.ts`
- Modify: `dashboard/src/hooks/useModels.ts`

- [ ] **Step 1: Add failing dashboard unit coverage for the new types and hooks**

Create or extend lightweight tests that prove:

- runtime-config mapping preserves `modelRegistry.repositoryUrl` and `modelRegistry.branch`,
- model resolve hook/api surface exposes `defaultSettings`, `configSource`, and `cacheStatus`.

If a full hook test is awkward in the current test stack, cover the pure mapping helpers instead.

- [ ] **Step 2: Run the targeted dashboard test files**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/ModelCatalogTab.test.tsx src/pages/settings/models/modelCatalogTypes.test.ts
```

Expected: FAIL because the new runtime-config and resolve plumbing does not exist yet.

- [ ] **Step 3: Extend `dashboard/src/api/settings.ts`**

Add:

```ts
export interface ModelRegistryConfig {
  repositoryUrl: string | null;
  branch: string | null;
}
```

Then include `modelRegistry` in:

- `RuntimeConfigUiRecord`
- `RuntimeConfig`
- `toUiRuntimeConfig(...)`
- `toBackendRuntimeConfig(...)`

- [ ] **Step 4: Add a generic runtime-config mutation hook**

In `dashboard/src/hooks/useSettings.ts`, add:

```ts
export function useUpdateRuntimeConfig(): UseMutationResult<
  Awaited<ReturnType<typeof updateRuntimeConfig>>,
  unknown,
  RuntimeConfig
>
```

Invalidate `['runtime-config']` on success.

- [ ] **Step 5: Add the model-registry resolve API**

In `dashboard/src/api/models.ts`, add request/response types and:

```ts
export async function resolveModelRegistryDefaults(
  provider: string,
  modelId: string,
): Promise<ResolveModelRegistryResponse>
```

Then expose it through a mutation hook in `dashboard/src/hooks/useModels.ts`.

- [ ] **Step 6: Re-run the targeted dashboard tests**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/ModelCatalogTab.test.tsx src/pages/settings/models/modelCatalogTypes.test.ts
```

Expected: PASS for the pure plumbing layer.

- [ ] **Step 7: Commit the dashboard API layer**

```bash
git add dashboard/src/api/settings.ts \
  dashboard/src/hooks/useSettings.ts \
  dashboard/src/api/models.ts \
  dashboard/src/hooks/useModels.ts
git commit -m "feat(model-catalog): add dashboard model registry api"
```

## Task 6: Add The Model Registry Source Card To Model Catalog

**Files:**
- Modify: `dashboard/src/pages/SettingsPage.tsx`
- Modify: `dashboard/src/pages/settings/ModelCatalogTab.tsx`
- Create: `dashboard/src/pages/settings/models/ModelRegistrySourceCard.tsx`
- Test: `dashboard/src/pages/settings/ModelCatalogTab.test.tsx`

- [ ] **Step 1: Add a failing render test for the source card**

Create `ModelCatalogTab.test.tsx` that proves:

- the Model Catalog page renders a `Model Registry Source` card,
- the current repository URL and branch are visible,
- the editor stays collapsed by default,
- unsaved state appears only when the form diverges.

Use the same `renderToStaticMarkup(...)` pattern already used by `SkillsMarketplacePanel.test.tsx`.

- [ ] **Step 2: Run the targeted source-card test**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/ModelCatalogTab.test.tsx
```

Expected: FAIL because the source card does not exist yet.

- [ ] **Step 3: Pass runtime-config context into Model Catalog**

Update `SettingsPage.tsx` so `ModelCatalogTab` receives the full runtime config, not only `llmConfig`.

Recommended prop shape:

```tsx
<ModelCatalogTab runtimeConfig={rc} />
```

- [ ] **Step 4: Create `ModelRegistrySourceCard.tsx`**

Follow the existing source-card presentation style from the skills marketplace, but keep the scope smaller:

- show current source summary,
- allow editing `repositoryUrl` and `branch`,
- save through `useUpdateRuntimeConfig`,
- avoid extra source modes or reload controls.

- [ ] **Step 5: Integrate the card into `ModelCatalogTab.tsx`**

Keep `ModelCatalogTab.tsx` as orchestration only:

- compute provider summaries,
- render provider overview card,
- render `ModelRegistrySourceCard`,
- render `ModelCatalogEditor`.

- [ ] **Step 6: Re-run the targeted source-card test**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/ModelCatalogTab.test.tsx
```

Expected: PASS with the new collapsed source card.

- [ ] **Step 7: Commit the source-card UI**

```bash
git add dashboard/src/pages/SettingsPage.tsx \
  dashboard/src/pages/settings/ModelCatalogTab.tsx \
  dashboard/src/pages/settings/models/ModelRegistrySourceCard.tsx \
  dashboard/src/pages/settings/ModelCatalogTab.test.tsx
git commit -m "feat(model-catalog): add model registry source card"
```

## Task 7: Resolve Registry Defaults Before Draft Creation

**Files:**
- Modify: `dashboard/src/pages/settings/models/AvailableModelInsertModal.tsx`
- Modify: `dashboard/src/pages/settings/models/ModelCatalogEditor.tsx`
- Modify: `dashboard/src/pages/settings/models/modelCatalogTypes.ts`
- Test: `dashboard/src/pages/settings/models/modelCatalogTypes.test.ts`

- [ ] **Step 1: Add failing unit tests for resolved draft construction**

Create `modelCatalogTypes.test.ts` to prove:

- resolved `defaultSettings` become the draft body,
- provider comes from the resolved settings response,
- duplicate-id provider scoping still works,
- null resolved defaults fall back to the current hardcoded defaults.

Suggested helper target:

```ts
export function createDraftFromResolvedSuggestion(
  suggestion: DiscoveredProviderModel,
  resolvedSettings: ModelSettings | null,
  modelsConfig: ModelsConfig | null | undefined,
): ModelDraft
```

- [ ] **Step 2: Run the targeted draft-helper tests**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/models/modelCatalogTypes.test.ts
```

Expected: FAIL because resolved draft helpers do not exist yet.

- [ ] **Step 3: Add resolved draft helpers in `modelCatalogTypes.ts`**

Keep the existing `resolveSuggestedModelId(...)` logic, then branch:

- if resolved settings exist, map them through `toModelDraft(...)` semantics,
- otherwise keep the existing fallback defaults.

- [ ] **Step 4: Update `ModelCatalogEditor.tsx` to await resolution**

Replace the direct `handleSuggestionSelect(suggestion)` path with an async flow:

1. call the resolve mutation,
2. receive `defaultSettings`,
3. build the draft via the new helper,
4. keep the existing `startTransition(...)` selection behavior.

Add explicit toast handling for unexpected resolve failures, but do not block fallback behavior when the backend returns a normal miss.

- [ ] **Step 5: Update `AvailableModelInsertModal.tsx` to expose pending selection state**

Track a selected pending model key so the clicked item:

- shows a loading state,
- cannot be clicked repeatedly during resolve,
- keeps the rest of the discovery list readable.

Do not move registry logic into the discovery query itself.

- [ ] **Step 6: Re-run the targeted dashboard tests**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/models/modelCatalogTypes.test.ts src/pages/settings/ModelCatalogTab.test.tsx
```

Expected: PASS, with draft behavior covered and the source card unaffected.

- [ ] **Step 7: Commit the resolved-draft flow**

```bash
git add dashboard/src/pages/settings/models/AvailableModelInsertModal.tsx \
  dashboard/src/pages/settings/models/ModelCatalogEditor.tsx \
  dashboard/src/pages/settings/models/modelCatalogTypes.ts \
  dashboard/src/pages/settings/models/modelCatalogTypes.test.ts
git commit -m "feat(model-catalog): resolve defaults before model insert"
```

## Task 8: Update User-Facing Documentation And Run Full Verification

**Files:**
- Modify: `docs/CONFIGURATION.md`
- Modify: `docs/DASHBOARD.md`
- Modify: `docs/MODEL_ROUTING.md`

- [ ] **Step 1: Update configuration and dashboard docs**

Document:

- the new `modelRegistry` runtime-config section,
- the `POST /api/models/registry/resolve` endpoint,
- the on-demand defaults flow in Model Catalog,
- one-day cache TTL and stale-cache fallback behavior.

- [ ] **Step 2: Run focused backend verification**

Run:

```bash
./mvnw -Dtest=RuntimeConfigServiceTest,SettingsControllerTest,ModelRegistryServiceTest,ModelsControllerTest test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Run focused dashboard verification**

Run:

```bash
cd dashboard
npm run test -- src/pages/settings/ModelCatalogTab.test.tsx src/pages/settings/models/modelCatalogTypes.test.ts
npm run lint
npm run build
```

Expected:

- Vitest exits `0`,
- ESLint exits `0`,
- Vite build succeeds.

- [ ] **Step 4: Run the full backend test suite**

Run: `./mvnw test`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Check modified dashboard file sizes**

Run:

```bash
wc -l \
  dashboard/src/pages/settings/ModelCatalogTab.tsx \
  dashboard/src/pages/settings/models/ModelRegistrySourceCard.tsx \
  dashboard/src/pages/settings/models/AvailableModelInsertModal.tsx \
  dashboard/src/pages/settings/models/ModelCatalogEditor.tsx
```

Expected: no file exceeds the dashboard 400-line rule.

- [ ] **Step 6: Commit docs and verification-safe cleanup**

```bash
git add docs/CONFIGURATION.md docs/DASHBOARD.md docs/MODEL_ROUTING.md
git commit -m "docs(model-catalog): document model registry discovery"
```

- [ ] **Step 7: Prepare the branch for execution handoff**

Run:

```bash
git status --short
git log --oneline --decorate -8
```

Expected:

- clean working tree,
- a linear sequence of focused commits for runtime config, service, controller, dashboard plumbing, source card, resolved draft flow, and docs.

