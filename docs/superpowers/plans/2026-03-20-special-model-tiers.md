# Special Model Tiers Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add canonical `special1..special5` model tiers that are configurable in the same UI as existing tiers, available for explicit skill/plan/reflection/manual selection, never chosen by implicit automatic routing, and fail loudly when explicitly referenced without a valid binding.

**Architecture:** Replace scattered hardcoded tier lists with one canonical backend tier catalog and one mirrored dashboard tier catalog. Migrate `ModelRouterConfig` from flat fields to canonical tier bindings with backward-compatible legacy loading, then route all backend/UI validation and rendering through that catalog. Split model selection into explicit-tier resolution that can fail and implicit-tier resolution that only considers normal routing tiers.

**Tech Stack:** Spring Boot 4, Java 17, Lombok, Jackson, React 18, TypeScript, Vite, React Query, Vitest, JUnit 5, Mockito.

---

### Task 1: Introduce Canonical Backend Tier Catalog And Canonical Router Shape

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/model/ModelTierCatalog.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java`

- [ ] **Step 1: Write failing runtime-config tests for canonical tier order and legacy router migration**

Add tests in `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java` that assert:
- the ordered explicit tiers are exactly `balanced`, `smart`, `deep`, `coding`, `special1`, `special2`, `special3`, `special4`, `special5`
- a legacy flat `model-router.json` with `balancedModel`, `smartModel`, `deepModel`, `codingModel`, and `routingModel` loads into canonical tier bindings
- a canonical `ModelRouterConfig` persists ordered tier bindings without losing values

- [ ] **Step 2: Run targeted runtime-config tests and confirm the new cases fail**

Run:
```bash
./mvnw -q -Dtest=RuntimeConfigServiceTest test
```

Expected:
- FAIL because `ModelTierCatalog` does not exist yet
- FAIL because `ModelRouterConfig` still uses flat fields only

- [ ] **Step 3: Add canonical backend tier catalog**

Create `src/main/java/me/golemcore/bot/domain/model/ModelTierCatalog.java` with:
- `ROUTING_TIER = "routing"`
- `ORDERED_EXPLICIT_TIERS = List.of("balanced", "smart", "deep", "coding", "special1", "special2", "special3", "special4", "special5")`
- `IMPLICIT_ROUTING_TIERS = Set.of("balanced", "smart", "deep", "coding")`
- helper methods:
  - `isKnownTier(String tier)`
  - `isExplicitSelectableTier(String tier)`
  - `isImplicitRoutingTier(String tier)`
  - `orderedExplicitTiers()`

- [ ] **Step 4: Replace flat router fields with canonical tier bindings plus backward-compatible migration**

Modify `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`:
- replace flat model-router tier fields with:
  - `TierBinding routing`
  - `LinkedHashMap<String, TierBinding> tiers`
- add nested `TierBinding`

Modify `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`:
- normalize `modelRouter.tiers` into the catalog’s fixed order
- migrate legacy flat router fields into canonical `routing` + `tiers` on load
- write canonical structure on persist
- keep existing default models and reasonings by seeding missing canonical bindings

- [ ] **Step 5: Re-run runtime-config tests and verify migration behavior**

Run:
```bash
./mvnw -q -Dtest=RuntimeConfigServiceTest test
```

Expected:
- PASS for the new migration and ordering coverage

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/golemcore/bot/domain/model/ModelTierCatalog.java \
  src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java \
  src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java \
  src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java
git commit -m "refactor(models): add canonical tier catalog"
```

### Task 2: Split Explicit And Implicit Model Resolution

**Files:**
- Modify: `src/main/java/me/golemcore/bot/domain/service/ModelSelectionService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java`
- Modify: `src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java`
- Modify: `src/main/java/me/golemcore/bot/domain/system/DynamicTierSystem.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/ModelSelectionServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystemTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/system/DynamicTierSystemTest.java`

- [ ] **Step 1: Write failing resolver tests for explicit specials and implicit non-special behavior**

Extend `src/test/java/me/golemcore/bot/domain/service/ModelSelectionServiceTest.java` to cover:
- explicit `special1` resolves when configured
- explicit `special3` throws when tier is unconfigured
- explicit `special2` throws when model is unknown
- explicit `special4` throws when provider is unavailable
- implicit resolution never returns a `special*` tier

Extend `src/test/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystemTest.java` to replace the current “unknown tier falls back to balanced” assumption with explicit failure behavior for invalid explicit tiers.

Extend `src/test/java/me/golemcore/bot/domain/system/DynamicTierSystemTest.java` to assert that dynamic upgrades still only target `coding`.

- [ ] **Step 2: Run targeted model-selection tests and confirm failures**

Run:
```bash
./mvnw -q -Dtest=ModelSelectionServiceTest,DefaultToolLoopSystemTest,DynamicTierSystemTest test
```

Expected:
- FAIL because resolver still uses hardcoded switch logic and unknown tiers still fall through

- [ ] **Step 3: Refactor model selection into explicit vs implicit APIs**

Modify `src/main/java/me/golemcore/bot/domain/service/ModelSelectionService.java`:
- add explicit resolver methods:
  - `resolveForExplicitTier(String tier)`
  - `resolveForImplicitTier(String tier)`
  - `resolveForContext(AgentContext context)`
- validate tier ids against `ModelTierCatalog`
- load bindings from canonical router config
- validate:
  - tier known
  - binding present for explicit tier
  - model exists in `ModelConfigService`
  - provider is configured in runtime config
  - reasoning level is valid when specified
- preserve user overrides for public tiers and explicit selections where applicable

- [ ] **Step 4: Wire explicit failures into request execution**

Modify `src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java` and `src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java` so that:
- explicit tiers from skills, plan mode, reflection, preferences, or manual selection go through explicit resolution
- implicit normal flow still defaults through non-special routing tiers only
- explicit invalid/unconfigured tiers become user-visible errors instead of silent fallbacks

Modify `src/main/java/me/golemcore/bot/domain/system/DynamicTierSystem.java` to use catalog helpers instead of raw tier string checks.

- [ ] **Step 5: Re-run targeted resolver tests**

Run:
```bash
./mvnw -q -Dtest=ModelSelectionServiceTest,DefaultToolLoopSystemTest,DynamicTierSystemTest test
```

Expected:
- PASS for explicit `special*` resolution and explicit failure cases
- PASS for dynamic tier staying limited to `coding`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/golemcore/bot/domain/service/ModelSelectionService.java \
  src/main/java/me/golemcore/bot/domain/system/ContextBuildingSystem.java \
  src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java \
  src/main/java/me/golemcore/bot/domain/system/DynamicTierSystem.java \
  src/test/java/me/golemcore/bot/domain/service/ModelSelectionServiceTest.java \
  src/test/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystemTest.java \
  src/test/java/me/golemcore/bot/domain/system/DynamicTierSystemTest.java
git commit -m "feat(models): split explicit and implicit tier resolution"
```

### Task 3: Unify Explicit Tier Validation Across Commands, Tools, And Skill Metadata

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java`
- Modify: `src/main/java/me/golemcore/bot/tools/TierTool.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SkillsController.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/PlansController.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/GoalsController.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/command/CommandRouterTest.java`
- Test: `src/test/java/me/golemcore/bot/tools/TierToolTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SkillsControllerTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/PlansControllerTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/GoalsControllerTest.java`

- [ ] **Step 1: Write failing tests for special-tier acceptance and fixed listing order**

Add test coverage for:
- `/tier special1` accepted
- `/model list` prints `balanced`, `smart`, `deep`, `coding`, `special1..special5` in fixed order
- `TierTool` accepts `special1..special5`
- skill metadata update allows known `model_tier` / `reflection_tier` ids and rejects unknown ones
- settings/provider deletion sees special-tier bindings as in-use
- plan and goals endpoints accept `special*` tier ids in payloads

- [ ] **Step 2: Run targeted command/controller tests and confirm failures**

Run:
```bash
./mvnw -q -Dtest=CommandRouterTest,TierToolTest,SkillsControllerTest,SettingsControllerTest,PlansControllerTest,GoalsControllerTest test
```

Expected:
- FAIL because commands and tools still hardcode four tiers
- FAIL because provider usage checks ignore special tiers

- [ ] **Step 3: Replace hardcoded tier lists with catalog-backed validation**

Modify:
- `src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java`
- `src/main/java/me/golemcore/bot/tools/TierTool.java`

Use `ModelTierCatalog.orderedExplicitTiers()` for:
- validation
- listing
- tool schema enum
- help text generation where needed

- [ ] **Step 4: Validate metadata and runtime references against the catalog**

Modify:
- `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SkillsController.java`
- `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/PlansController.java`
- `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/GoalsController.java`

Rules:
- blank tier still allowed where optional
- non-blank tier must be known to `ModelTierCatalog`
- leave configuration completeness checks to runtime resolution so removed/missing bindings still produce user-visible execution errors

Modify `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java` so provider usage and runtime config validation inspect every configured tier binding including `special1..special5`.

- [ ] **Step 5: Re-run targeted command/controller tests**

Run:
```bash
./mvnw -q -Dtest=CommandRouterTest,TierToolTest,SkillsControllerTest,SettingsControllerTest,PlansControllerTest,GoalsControllerTest test
```

Expected:
- PASS for special-tier acceptance, fixed order, and provider safety

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java \
  src/main/java/me/golemcore/bot/tools/TierTool.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SkillsController.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/PlansController.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/GoalsController.java \
  src/test/java/me/golemcore/bot/adapter/inbound/command/CommandRouterTest.java \
  src/test/java/me/golemcore/bot/tools/TierToolTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SkillsControllerTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/PlansControllerTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/GoalsControllerTest.java
git commit -m "feat(models): expose special tiers in explicit controls"
```

### Task 4: Add Shared Dashboard Tier Catalog And Canonical Router Types

**Files:**
- Create: `dashboard/src/features/models/modelTierCatalog.ts`
- Modify: `dashboard/src/api/settings.ts`
- Modify: `dashboard/src/pages/settings/ModelsTab.tsx`
- Modify: `dashboard/src/hooks/useSettings.ts`
- Test: `dashboard/src/features/models/modelTierCatalog.test.ts`

- [ ] **Step 1: Write failing dashboard tests for fixed tier order**

Create `dashboard/src/features/models/modelTierCatalog.test.ts` covering:
- explicit tier ids in fixed order
- labels for `Special 1` through `Special 5`
- helper that distinguishes explicit tiers from the `routing` slot

- [ ] **Step 2: Run dashboard tier-catalog tests and confirm failure**

Run:
```bash
cd dashboard && npm run test -- modelTierCatalog
```

Expected:
- FAIL because the shared dashboard catalog does not exist yet

- [ ] **Step 3: Add shared dashboard tier catalog and migrate API types**

Create `dashboard/src/features/models/modelTierCatalog.ts` exporting:
- ordered explicit tiers
- routing slot metadata
- labels
- helpers for normalization and known-tier checks

Modify `dashboard/src/api/settings.ts`:
- replace flat `ModelRouterConfig` fields with:
  - `routing`
  - `tiers`
  - `dynamicTierEnabled`
- update serialization/deserialization helpers to preserve tier-binding order

- [ ] **Step 4: Refactor models settings UI to render from the shared catalog**

Modify `dashboard/src/pages/settings/ModelsTab.tsx`:
- replace local four-tier array with catalog-driven cards
- render Routing separately, then explicit tiers in fixed order
- render `Special 1..5` cards automatically from the catalog

Keep save behavior unchanged apart from using the canonical router shape.

- [ ] **Step 5: Re-run dashboard catalog and build checks**

Run:
```bash
cd dashboard && npm run test -- modelTierCatalog
cd dashboard && npm run build
```

Expected:
- PASS for catalog tests
- PASS for dashboard build using canonical router types

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/features/models/modelTierCatalog.ts \
  dashboard/src/features/models/modelTierCatalog.test.ts \
  dashboard/src/api/settings.ts \
  dashboard/src/pages/settings/ModelsTab.tsx \
  dashboard/src/hooks/useSettings.ts
git commit -m "refactor(dashboard): share model tier catalog"
```

### Task 5: Migrate All Dashboard Tier Selectors, Normalizers, And Badges

**Files:**
- Modify: `dashboard/src/pages/settings/AutoModeTab.tsx`
- Modify: `dashboard/src/components/chat/ChatWindow.tsx`
- Modify: `dashboard/src/components/chat/ContextPanel.tsx`
- Modify: `dashboard/src/components/chat/MessageBubble.tsx`
- Modify: `dashboard/src/components/webhooks/HookMappingAgentSection.tsx`
- Modify: `dashboard/src/pages/skills/LocalSkillsPanelConstants.ts`
- Modify: `dashboard/src/pages/skills/LocalSkillDetailSections.tsx`
- Test: `dashboard/src/utils/systemUpdateUi.test.ts`
- Test: `dashboard/src/pages/skills/skillEditorDraft.test.ts`
- Test: `dashboard/src/components/chat/MessageBubble.test.tsx`

- [ ] **Step 1: Add or update focused UI tests for special-tier rendering and normalization**

Extend existing tests to cover:
- chat tier normalization accepts `special1..special5`
- message bubble renders `Special 3` style labels correctly
- skill editor dropdown includes special tiers in fixed order

- [ ] **Step 2: Run focused dashboard tests and confirm current failures**

Run:
```bash
cd dashboard && npm run test -- skillEditorDraft MessageBubble
```

Expected:
- FAIL because UI still hardcodes only four tiers

- [ ] **Step 3: Replace local tier lists with catalog helpers**

Modify:
- `dashboard/src/pages/settings/AutoModeTab.tsx`
- `dashboard/src/components/chat/ChatWindow.tsx`
- `dashboard/src/components/chat/ContextPanel.tsx`
- `dashboard/src/components/chat/MessageBubble.tsx`
- `dashboard/src/components/webhooks/HookMappingAgentSection.tsx`
- `dashboard/src/pages/skills/LocalSkillsPanelConstants.ts`
- `dashboard/src/pages/skills/LocalSkillDetailSections.tsx`

Use the shared catalog for:
- dropdown options
- tier normalization
- badges/labels/colors

- [ ] **Step 4: Re-run dashboard lint, targeted tests, and build**

Run:
```bash
cd dashboard && npm run test -- skillEditorDraft MessageBubble
cd dashboard && npm run lint
cd dashboard && npm run build
```

Expected:
- PASS for tests
- PASS for lint
- PASS for build

- [ ] **Step 5: Commit**

```bash
git add dashboard/src/pages/settings/AutoModeTab.tsx \
  dashboard/src/components/chat/ChatWindow.tsx \
  dashboard/src/components/chat/ContextPanel.tsx \
  dashboard/src/components/chat/MessageBubble.tsx \
  dashboard/src/components/webhooks/HookMappingAgentSection.tsx \
  dashboard/src/pages/skills/LocalSkillsPanelConstants.ts \
  dashboard/src/pages/skills/LocalSkillDetailSections.tsx \
  dashboard/src/pages/skills/skillEditorDraft.test.ts \
  dashboard/src/components/chat/MessageBubble.test.tsx
git commit -m "feat(dashboard): add special tier selectors"
```

### Task 6: Full Regression Verification

**Files:**
- Modify as needed based on failures discovered during verification
- Test: `src/test/java/me/golemcore/bot/...`
- Test: `dashboard/src/...`

- [ ] **Step 1: Run targeted backend regression suite**

Run:
```bash
./mvnw -q -Dtest=RuntimeConfigServiceTest,ModelSelectionServiceTest,CommandRouterTest,TierToolTest,SettingsControllerTest,SkillsControllerTest,PlansControllerTest,GoalsControllerTest,DefaultToolLoopSystemTest,DynamicTierSystemTest test
```

Expected:
- PASS for all special-tier coverage

- [ ] **Step 2: Run full backend quality gates**

Run:
```bash
./mvnw -q -Dmaven.gitcommitid.skip=true formatter:validate pmd:check
./mvnw -q -Dmaven.gitcommitid.skip=true compile spotbugs:check
./mvnw -q -Dmaven.gitcommitid.skip=true clean verify -DskipGitHooks=true
```

Expected:
- PASS with no formatter, PMD, SpotBugs, or test failures

- [ ] **Step 3: Run dashboard verification**

Run:
```bash
cd dashboard && npm run lint
cd dashboard && npm run build
```

Expected:
- PASS with no new lint or build failures

- [ ] **Step 4: Review git diff for forbidden leftovers**

Check:
```bash
git diff --stat
git diff --check
```

Expected:
- no whitespace issues
- no accidental docs-only drift outside the intended plan/spec files

- [ ] **Step 5: Commit final fixes**

```bash
git add .
git commit -m "test(models): verify special tier routing coverage"
```

