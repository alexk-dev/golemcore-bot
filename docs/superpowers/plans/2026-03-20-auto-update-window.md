# Auto Update Window Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add automatic self-update with a UTC maintenance window, runtime-idle safety gating, and post-start cleanup of old jars while keeping the existing manual update flow intact.

**Architecture:** Extend the existing update subsystem instead of routing updates through the generic scheduler stack. Add a runtime-config-backed update settings section, a dedicated activity gate, a maintenance-window helper, and a post-start cleanup service. Keep `UpdateService` as the facade/controller entry point, but move new policy logic into small focused collaborators.

**Tech Stack:** Spring Boot 4, Java 17, Jackson, Reactor controller endpoints, React 18, TypeScript, React Query, React Bootstrap, SCSS, JUnit 5, Mockito.

---

### Task 1: Add Runtime Update Configuration And Waiting States

**Files:**
- Modify: `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/UpdateState.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/UpdateStatus.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java`

- [ ] **Step 1: Write failing runtime-config tests for the new update section**

Add tests covering:
- default `UpdateConfig`
- normalization of invalid interval/time values
- persistence/load of `update.json`
- inclusion of `ConfigSection.UPDATE`

- [ ] **Step 2: Run targeted tests to verify they fail for the missing config section**

Run: `./mvnw -q -Dtest=RuntimeConfigServiceTest test`

Expected: FAIL with missing `UpdateConfig`/`ConfigSection.UPDATE` behavior.

- [ ] **Step 3: Add `RuntimeConfig.UpdateConfig` and register `update.json`**

Implement:
- new nested `UpdateConfig`
- builder defaults
- `ConfigSection.UPDATE`
- `load/persist/normalize` support in `RuntimeConfigService`
- getters for:
  - `isUpdateAutoEnabled()`
  - `getUpdateCheckInterval()`
  - `isUpdateMaintenanceWindowEnabled()`
  - `getUpdateMaintenanceWindowStartUtc()`
  - `getUpdateMaintenanceWindowEndUtc()`

- [ ] **Step 4: Extend update states for orchestration waiting**

Add:
- `WAITING_FOR_WINDOW`
- `WAITING_FOR_IDLE`

Keep existing consumers compiling.

- [ ] **Step 5: Re-run the targeted runtime-config tests**

Run: `./mvnw -q -Dtest=RuntimeConfigServiceTest test`

Expected: PASS

- [ ] **Step 6: Commit the config/state foundation**

```bash
git add src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java \
  src/main/java/me/golemcore/bot/domain/model/UpdateState.java \
  src/main/java/me/golemcore/bot/domain/model/UpdateStatus.java \
  src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java \
  src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java
git commit -m "feat(update): add runtime auto-update config"
```

### Task 2: Add Runtime Safety Gate Contracts

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/model/UpdateBlocker.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/UpdateActivityGate.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/SessionRunCoordinator.java`
- Modify: `src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/SessionRunCoordinatorTest.java`
- Test: `src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/UpdateActivityGateTest.java`

- [ ] **Step 1: Write failing tests for the update activity gate**

Cover:
- active session run blocks update
- queued session work blocks update
- auto scheduler execution blocks update
- fully idle runtime allows update

- [ ] **Step 2: Run the new and affected targeted tests**

Run: `./mvnw -q -Dtest=UpdateActivityGateTest,SessionRunCoordinatorTest,AutoModeSchedulerTest test`

Expected: FAIL with missing query/gate behavior.

- [ ] **Step 3: Add explicit query methods to runtime executors**

Implement:
- `SessionRunCoordinator.hasActiveOrQueuedWork()`
- `AutoModeScheduler.isExecuting()`

Do not inspect private fields externally via reflection.

- [ ] **Step 4: Implement `UpdateBlocker` and `UpdateActivityGate`**

`UpdateActivityGate` should return structured reasons:
- `ACTIVE_SESSION_RUN`
- `QUEUED_SESSION_WORK`
- `AUTO_JOB_RUNNING`

- [ ] **Step 5: Re-run the targeted gate tests**

Run: `./mvnw -q -Dtest=UpdateActivityGateTest,SessionRunCoordinatorTest,AutoModeSchedulerTest test`

Expected: PASS

- [ ] **Step 6: Commit the safety gate layer**

```bash
git add src/main/java/me/golemcore/bot/domain/model/UpdateBlocker.java \
  src/main/java/me/golemcore/bot/domain/service/UpdateActivityGate.java \
  src/main/java/me/golemcore/bot/domain/service/SessionRunCoordinator.java \
  src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java \
  src/test/java/me/golemcore/bot/domain/service/UpdateActivityGateTest.java \
  src/test/java/me/golemcore/bot/domain/service/SessionRunCoordinatorTest.java \
  src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java
git commit -m "feat(update): add runtime activity gate"
```

### Task 3: Implement Maintenance Window And Auto-Orchestrated Update Flow

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/service/UpdateMaintenanceWindow.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/UpdateService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/UpdateStatus.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/UpdateMaintenanceWindowTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/UpdateServiceTest.java`

- [ ] **Step 1: Write failing maintenance-window and orchestration tests**

Cover:
- window disabled => open
- standard and overnight windows
- auto tick checks by interval
- staged update waits for window
- staged update waits for idle
- manual `updateNow()` conflicts when work is active
- auto update enabled by default
- no cleanup during prepare

- [ ] **Step 2: Run targeted update tests to verify the failures**

Run: `./mvnw -q -Dtest=UpdateMaintenanceWindowTest,UpdateServiceTest test`

Expected: FAIL with missing helper/state/auto behavior.

- [ ] **Step 3: Implement `UpdateMaintenanceWindow`**

Implement:
- UTC window evaluation
- disabled-window fast path
- overnight support
- `nextEligibleAt`

- [ ] **Step 4: Refactor `UpdateService` into explicit orchestration phases**

Implement:
- periodic auto tick scheduler
- interval-based auto check
- staged-first auto logic
- waiting states
- status metadata:
  - `autoEnabled`
  - `maintenanceWindowEnabled`
  - `maintenanceWindowStartUtc`
  - `maintenanceWindowEndUtc`
  - `serverTimezone`
  - `busy`
  - `windowOpen`
  - `blockedReason`
  - `nextEligibleAt`

- [ ] **Step 5: Preserve manual workflow semantics**

Implement:
- `update-now` bypasses maintenance window
- `update-now` does not bypass `UpdateActivityGate`
- return `IllegalStateException`/409 with a clear busy reason

- [ ] **Step 6: Re-run the targeted update tests**

Run: `./mvnw -q -Dtest=UpdateMaintenanceWindowTest,UpdateServiceTest test`

Expected: PASS

- [ ] **Step 7: Commit auto-orchestration behavior**

```bash
git add src/main/java/me/golemcore/bot/domain/service/UpdateMaintenanceWindow.java \
  src/main/java/me/golemcore/bot/domain/service/UpdateService.java \
  src/main/java/me/golemcore/bot/domain/model/UpdateStatus.java \
  src/test/java/me/golemcore/bot/domain/service/UpdateMaintenanceWindowTest.java \
  src/test/java/me/golemcore/bot/domain/service/UpdateServiceTest.java
git commit -m "feat(update): add auto update orchestration"
```

### Task 4: Add Update Config Endpoints And Post-Start Cleanup

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/UpdateService.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/UpdateController.java`
- Modify: `src/main/java/me/golemcore/bot/launcher/RuntimeLauncher.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/UpdateControllerTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/UpdateControllerWebTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/launcher/RuntimeLauncherTest.java`

- [ ] **Step 1: Write failing controller and cleanup tests**

Cover:
- get/update update-config endpoints
- conflict mapping for busy manual update
- post-start cleanup keeps only `current + staged`
- startup cleanup runs after successful launch handoff, not prepare

- [ ] **Step 2: Run targeted endpoint/cleanup tests**

Run: `./mvnw -q -Dtest=UpdateControllerTest,UpdateControllerWebTest,UpdateRuntimeCleanupServiceTest,RuntimeLauncherTest test`

Expected: FAIL with missing endpoints/cleanup behavior.

- [ ] **Step 3: Implement dedicated update-config endpoints**

Add:
- `GET /api/system/update/config`
- `PUT /api/system/update/config`

Use the runtime-config update section rather than the entire runtime config document.

- [ ] **Step 4: Implement post-start cleanup service**

Behavior:
- keep current jar
- keep staged jar only if distinct and valid
- delete everything else
- never run during prepare

- [ ] **Step 5: Wire startup recovery**

Trigger cleanup only after the new runtime starts successfully and can inspect markers safely.

- [ ] **Step 6: Re-run targeted endpoint/cleanup tests**

Run: `./mvnw -q -Dtest=UpdateControllerTest,UpdateControllerWebTest,UpdateRuntimeCleanupServiceTest,RuntimeLauncherTest test`

Expected: PASS

- [ ] **Step 7: Commit API and cleanup integration**

```bash
git add src/main/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupService.java \
  src/main/java/me/golemcore/bot/domain/service/UpdateService.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/UpdateController.java \
  src/main/java/me/golemcore/bot/launcher/RuntimeLauncher.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/UpdateControllerTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/UpdateControllerWebTest.java \
  src/test/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupServiceTest.java \
  src/test/java/me/golemcore/bot/launcher/RuntimeLauncherTest.java
git commit -m "feat(update): add config endpoints and startup cleanup"
```

### Task 5: Add Dashboard Auto-Update Settings And Waiting-State UX

**Files:**
- Modify: `dashboard/src/api/system.ts`
- Modify: `dashboard/src/hooks/useSystem.ts`
- Create: `dashboard/src/components/settings/AutoUpdateSettingsPanel.tsx`
- Create: `dashboard/src/utils/updateWindowTime.ts`
- Modify: `dashboard/src/pages/settings/UpdatesTab.tsx`
- Modify: `dashboard/src/utils/systemUpdateUi.ts`
- Test: `dashboard/src/utils/systemUpdateUi.test.ts`
- Test: `dashboard/src/utils/updateWindowTime.test.ts`
- Test: `dashboard/src/pages/settings/UpdatesTab.test.tsx` or a split component test if that file becomes too large

- [ ] **Step 1: Write failing dashboard tests**

Cover:
- timezone conversion local <-> UTC
- explicit timezone labeling
- waiting-state descriptions
- settings load/save actions

- [ ] **Step 2: Run targeted dashboard tests to verify they fail**

Run:
```bash
cd dashboard
npm test -- --runInBand systemUpdateUi updateWindowTime UpdatesTab
```

Expected: FAIL with missing config/timezone UI behavior.

- [ ] **Step 3: Extend the update API client and hooks**

Add typed support for:
- get update config
- save update config
- extended update status fields

- [ ] **Step 4: Extract a focused settings panel**

Implement `AutoUpdateSettingsPanel.tsx` with:
- auto-update toggle
- interval control
- any-time vs maintenance-window mode
- local-time editors
- explicit timezone display and UTC summary

- [ ] **Step 5: Update workflow/status presentation**

Implement UI support for:
- `WAITING_FOR_WINDOW`
- `WAITING_FOR_IDLE`
- blocker reasons
- server timezone display

- [ ] **Step 6: Run dashboard verification**

Run:
```bash
cd dashboard
npm run lint
npm run build
```

Then run targeted tests again:
```bash
cd dashboard
npm test -- --runInBand systemUpdateUi updateWindowTime UpdatesTab
```

Expected: PASS

- [ ] **Step 7: Commit dashboard changes**

```bash
git add dashboard/src/api/system.ts \
  dashboard/src/hooks/useSystem.ts \
  dashboard/src/components/settings/AutoUpdateSettingsPanel.tsx \
  dashboard/src/utils/updateWindowTime.ts \
  dashboard/src/utils/updateWindowTime.test.ts \
  dashboard/src/pages/settings/UpdatesTab.tsx \
  dashboard/src/utils/systemUpdateUi.ts \
  dashboard/src/utils/systemUpdateUi.test.ts
git commit -m "feat(update): add dashboard auto update settings"
```

### Task 6: Full Verification And Branch Finish

**Files:**
- Verify all modified files from Tasks 1-5

- [ ] **Step 1: Run backend quality gates**

Run:
```bash
./mvnw -q formatter:validate pmd:check
./mvnw -q compile spotbugs:check
./mvnw -q clean verify
```

Expected: PASS

- [ ] **Step 2: Run dashboard quality gates again if frontend changed after backend verification**

Run:
```bash
cd dashboard
npm run lint
npm run build
```

Expected: PASS

- [ ] **Step 3: Review the final diff critically**

Check:
- no accidental `main` branch edits outside the feature branch
- no cleanup during prepare
- manual `update-now` blocked by activity gate
- window stored in UTC and shown explicitly in UI
- post-start cleanup enforces `current + staged`

- [ ] **Step 4: Commit any final fixes**

```bash
git add -A
git commit -m "fix(update): polish auto update workflow"
```

Only if additional fixes were needed after verification.

- [ ] **Step 5: Use finishing workflow**

After verification, use the finishing-a-development-branch workflow to decide whether to push/open PR/report ready.
