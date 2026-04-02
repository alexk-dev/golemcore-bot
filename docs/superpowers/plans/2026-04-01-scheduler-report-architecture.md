# Scheduler Report Architecture Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current primitive/sentinel-based scheduler report contract with explicit report configuration and update semantics, move report channel capability discovery to the backend, decouple report delivery from `AutoModeScheduler` internals, and extract scheduled run execution/report orchestration into a dedicated collaborator.

**Architecture:** Introduce a first-class `ScheduleReportConfig` domain object plus explicit update/clear semantics, expose ready-to-render report channel options from the scheduler API, and separate delivery context plus run execution logic into dedicated auto-mode collaborators. Keep scheduler responsibility focused on tick lifecycle and schedule-to-message selection while preserving current behavior.

**Tech Stack:** Spring Boot, Java 17, Jackson, React 18, TypeScript, TanStack Query, Vitest, JUnit 5, Mockito.

---

### Task 1: Introduce explicit scheduler report domain models

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/model/ScheduleReportConfig.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/ScheduleReportConfigUpdate.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/ScheduleEntry.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/ScheduleServiceTest.java`

- [ ] **Step 1: Write failing service tests for explicit set / clear / no-change semantics**

Add tests that assert:
- creating a schedule stores a nested report config
- updating with `ScheduleReportConfigUpdate.set(...)` replaces the config
- updating with `ScheduleReportConfigUpdate.clear()` removes the config
- updating with `ScheduleReportConfigUpdate.noChange()` preserves the config

- [ ] **Step 2: Run service tests to verify they fail for the missing domain types / API**

Run: `./mvnw -q -Dtest=ScheduleServiceTest test`
Expected: FAIL because the new update/config API does not exist yet.

- [ ] **Step 3: Implement `ScheduleReportConfig` and `ScheduleReportConfigUpdate`**

Model report configuration as a single nested object with:
- `channelType`
- `chatId`
- `webhookUrl`
- `webhookBearerToken`

Model update semantics as explicit operations:
- `noChange()`
- `clear()`
- `set(config)`

- [ ] **Step 4: Update `ScheduleEntry` and `ScheduleService` to use the new models**

Replace the four primitive report fields in `ScheduleEntry` with one `ScheduleReportConfig report`.
Update create/update service methods to accept and apply `ScheduleReportConfig` / `ScheduleReportConfigUpdate`.

- [ ] **Step 5: Re-run service tests**

Run: `./mvnw -q -Dtest=ScheduleServiceTest test`
Expected: PASS.

### Task 2: Replace scheduler API sentinel protocol with explicit report DTOs

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SchedulerController.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SchedulerControllerTest.java`
- Modify: `dashboard/src/api/scheduler.ts`
- Modify: `dashboard/src/hooks/useSchedulerForm.ts`
- Modify: `dashboard/src/hooks/useSchedulerForm.test.ts`
- Modify: `dashboard/src/components/scheduler/schedulerTypes.ts`

- [ ] **Step 1: Write failing controller and hook tests for explicit nested report payloads**

Add tests that assert:
- scheduler state returns nested `report`
- create accepts `report: {...}` and no longer needs primitive top-level report fields
- update clears report via explicit patch operation, not `reportChannelType: ''`
- form helpers parse/build the nested report contract

- [ ] **Step 2: Run focused backend/frontend tests to verify red**

Run: `./mvnw -q -Dtest=SchedulerControllerTest test`
Expected: FAIL because DTOs still use primitive fields.

Run: `npm test -- src/hooks/useSchedulerForm.test.ts`
Expected: FAIL because the frontend still builds the old contract.

- [ ] **Step 3: Refactor scheduler API records**

Add nested DTOs/records along these lines:
- `ScheduleReportDto`
- `ScheduleReportRequest`
- `ScheduleReportPatchRequest`
- `operation: SET | CLEAR`

Map them to `ScheduleReportConfig` and `ScheduleReportConfigUpdate`.

- [ ] **Step 4: Remove sentinel logic from frontend form helpers**

Update API types and hook helpers so:
- create sends `report: null` or `report: {...}`
- update sends `report: { operation: 'CLEAR' }` or `report: { operation: 'SET', config: {...} }`
- parsing/editing uses the nested `report` object

- [ ] **Step 5: Re-run focused backend/frontend tests**

Run: `./mvnw -q -Dtest=SchedulerControllerTest test`
Expected: PASS.

Run: `npm test -- src/hooks/useSchedulerForm.test.ts src/components/scheduler/schedulerFormUtils.test.ts`
Expected: PASS.

### Task 3: Move report channel capability discovery to the backend

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SchedulerController.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SchedulerControllerTest.java`
- Modify: `dashboard/src/pages/SchedulerPage.tsx`
- Modify: `dashboard/src/components/scheduler/SchedulerWorkspace.tsx`
- Modify: `dashboard/src/components/scheduler/SchedulerCreateCard.tsx`
- Modify: `dashboard/src/components/scheduler/SchedulerCreateCardSections.tsx`
- Modify: `dashboard/src/utils/channelUtils.ts`

- [ ] **Step 1: Write failing tests for backend-provided report channel options**

Add controller tests for `SchedulerStateResponse.reportChannelOptions` including:
- known channel labels
- webhook option
- suggested/default Telegram chat id derived from runtime config

- [ ] **Step 2: Run controller tests to verify red**

Run: `./mvnw -q -Dtest=SchedulerControllerTest test`
Expected: FAIL because scheduler state does not expose report channel options yet.

- [ ] **Step 3: Add backend-provided report channel options**

Expose `reportChannelOptions` from `SchedulerController` and build them from:
- `ChannelRegistry`
- `RuntimeConfigService` for suggested Telegram chat id

Keep validation in the backend aligned with the same capability list.

- [ ] **Step 4: Simplify dashboard wiring**

Update `SchedulerPage` and scheduler form components to consume `data.reportChannelOptions`.
Remove scheduler-specific capability inference and Telegram prefill logic from the page.
Keep `channelUtils.ts` only if still needed by `WebhooksPage`.

- [ ] **Step 5: Re-run focused tests**

Run: `./mvnw -q -Dtest=SchedulerControllerTest test`
Expected: PASS.

Run: `npm test -- src/hooks/useSchedulerForm.test.ts src/components/scheduler/schedulerFormUtils.test.ts`
Expected: PASS.

### Task 4: Decouple report delivery from `AutoModeScheduler` internals

**Files:**
- Create: `src/main/java/me/golemcore/bot/auto/ScheduleDeliveryContext.java`
- Modify: `src/main/java/me/golemcore/bot/auto/ScheduleReportSender.java`
- Modify: `src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java`
- Modify: `src/test/java/me/golemcore/bot/auto/ScheduleReportSenderTest.java`
- Modify: `src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java`

- [ ] **Step 1: Write failing tests against a top-level delivery context**

Update/add tests so `ScheduleReportSender` accepts a top-level context type rather than `AutoModeScheduler.ChannelInfo`.

- [ ] **Step 2: Run targeted auto-mode tests to verify red**

Run: `./mvnw -q -Dtest=ScheduleReportSenderTest,AutoModeSchedulerTest test`
Expected: FAIL because the sender still depends on `AutoModeScheduler.ChannelInfo`.

- [ ] **Step 3: Introduce `ScheduleDeliveryContext` and refactor sender/scheduler**

Create a top-level immutable context object carrying:
- `channelType`
- `sessionChatId`
- `transportChatId`

Use it for the scheduler’s stored channel binding and for report fallback resolution.

- [ ] **Step 4: Re-run targeted auto-mode tests**

Run: `./mvnw -q -Dtest=ScheduleReportSenderTest,AutoModeSchedulerTest test`
Expected: PASS.

### Task 5: Extract scheduled run execution/report orchestration out of `AutoModeScheduler`

**Files:**
- Create: `src/main/java/me/golemcore/bot/auto/ScheduledRunExecutor.java`
- Modify: `src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java`
- Modify: `src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java`

- [ ] **Step 1: Write failing tests that target the extracted execution boundary**

Add/adjust tests so `AutoModeScheduler` only verifies:
- schedule selection
- context clearing
- passing the right request/context into the executor

Move run success/failure/report behavior checks to `ScheduledRunExecutor` tests if needed.

- [ ] **Step 2: Run targeted auto-mode tests to verify red**

Run: `./mvnw -q -Dtest=AutoModeSchedulerTest test`
Expected: FAIL because the executor class does not exist yet and the scheduler still owns execution/report flow.

- [ ] **Step 3: Implement `ScheduledRunExecutor`**

Move into the executor:
- synthetic message construction
- submit/await
- success/failure state recording
- reflection reruns
- report emission

Leave in `AutoModeScheduler`:
- tick lifecycle
- due-schedule iteration
- schedule-to-message resolution
- channel registration and milestone notifications

- [ ] **Step 4: Re-run targeted auto-mode tests**

Run: `./mvnw -q -Dtest=AutoModeSchedulerTest,ScheduleReportSenderTest test`
Expected: PASS.

### Task 6: Full verification and branch hygiene

**Files:**
- Modify: `docs/superpowers/plans/2026-04-01-scheduler-report-architecture.md`

- [ ] **Step 1: Run focused backend verification**

Run: `./mvnw -q -Dtest=SchedulerControllerTest,ScheduleServiceTest,AutoModeSchedulerTest,ScheduleReportSenderTest,AutoModeLayerTest test`
Expected: PASS.

- [ ] **Step 2: Run focused frontend verification**

Run: `npm test -- src/hooks/useSchedulerForm.test.ts src/components/scheduler/schedulerFormUtils.test.ts`
Expected: PASS.

- [ ] **Step 3: Run dashboard build**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 4: Run whitespace / patch hygiene**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-04-01-scheduler-report-architecture.md \
  src/main/java/me/golemcore/bot/domain/model/ScheduleReportConfig.java \
  src/main/java/me/golemcore/bot/domain/model/ScheduleReportConfigUpdate.java \
  src/main/java/me/golemcore/bot/auto/ScheduleDeliveryContext.java \
  src/main/java/me/golemcore/bot/auto/ScheduledRunExecutor.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SchedulerController.java \
  src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java \
  src/main/java/me/golemcore/bot/auto/ScheduleReportSender.java \
  src/main/java/me/golemcore/bot/domain/model/ScheduleEntry.java \
  src/main/java/me/golemcore/bot/domain/service/ScheduleService.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SchedulerControllerTest.java \
  src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java \
  src/test/java/me/golemcore/bot/auto/ScheduleReportSenderTest.java \
  src/test/java/me/golemcore/bot/domain/service/ScheduleServiceTest.java \
  dashboard/src/api/scheduler.ts \
  dashboard/src/components/scheduler/SchedulerCreateCard.tsx \
  dashboard/src/components/scheduler/SchedulerCreateCardSections.tsx \
  dashboard/src/components/scheduler/SchedulerWorkspace.tsx \
  dashboard/src/components/scheduler/schedulerTypes.ts \
  dashboard/src/hooks/useSchedulerForm.ts \
  dashboard/src/hooks/useSchedulerForm.test.ts \
  dashboard/src/pages/SchedulerPage.tsx \
  dashboard/src/utils/channelUtils.ts
git commit -m "refactor(auto): separate scheduler report architecture"
```
