# Auto Update With Maintenance Window Design

## Goal

Add automatic self-update to `golemcore-bot` so that:

- automatic updates are enabled by default
- updates can be restricted to a configurable maintenance window
- update apply is blocked while user or scheduled work is in progress
- only two runtime jars are retained at once: the current jar and an optional staged jar
- old jars are cleaned up only after the new runtime starts successfully

## Current State

The bot already has a self-update subsystem centered on `UpdateService`.

Existing capabilities:

- check latest GitHub release
- download and verify a compatible jar
- stage and apply an update
- restart the runtime through the existing launcher flow
- expose manual update endpoints and dashboard status

Current gaps:

- no automatic polling/apply loop
- no maintenance window
- no runtime safety gate for active work
- cleanup behavior is size-based instead of enforcing a strict `current + staged` invariant
- update settings are not part of runtime config

## Approved Product Decisions

- Auto update is enabled by default.
- Default maintenance behavior is "any time".
- Server time is authoritative and is always UTC.
- Frontend uses the user's local timezone for editing and display, then converts to UTC for persistence.
- The UI must show timezone information explicitly to avoid confusion.
- Update apply must be blocked when:
  - `SessionRunCoordinator` has an active run
  - `SessionRunCoordinator` has queued work
  - `AutoModeScheduler` is currently executing a tick or dispatch
- Cleanup happens only after successful startup of the new runtime.
- Manual `update-now` bypasses the maintenance window but still respects the activity gate.

## Design Overview

The design keeps update orchestration inside the existing update subsystem and extends it with focused helper components instead of pushing update behavior into the generic scheduler stack.

Recommended structure:

- `UpdateService`
  - remains the public facade for status, check, and apply
  - coordinates both manual and automatic update workflows
- `UpdateActivityGate`
  - answers whether update apply is safe right now
  - reports a structured blocker reason when it is not
- `UpdateMaintenanceWindow`
  - evaluates whether the current UTC time is inside the configured maintenance window
  - computes the next eligible time if outside the window
- `UpdateRuntimeCleanupService`
  - runs only after successful startup
  - removes old jars so the update directory converges to the `current + staged` invariant

This keeps update behavior inside the update domain while avoiding further growth of the existing `UpdateService` into a monolith.

## Runtime Behavior

### Automatic Flow

On startup, the update subsystem schedules a periodic background tick.

Default behavior:

- auto update enabled
- check interval: `60` minutes
- maintenance window disabled, meaning updates may apply at any time

Tick flow:

1. If the update subsystem is disabled, do nothing.
2. If an update workflow is already busy (`CHECKING`, `PREPARING`, `APPLYING`, `VERIFYING`), skip the tick.
3. If a staged update already exists:
   - evaluate maintenance window
   - evaluate activity gate
   - apply immediately only if both are clear
   - otherwise publish a waiting state
4. If no staged update exists:
   - check for a compatible release
   - if none exists, return to idle
   - if a compatible release exists, download and verify it
   - stage it locally
   - wait until the window is open and the bot is idle, then apply

### Waiting Semantics

If a staged update cannot apply yet:

- outside maintenance window -> `WAITING_FOR_WINDOW`
- inside maintenance window but runtime is busy -> `WAITING_FOR_IDLE`

These are not failures. They are normal orchestration states.

### Manual Apply

`update-now` behavior:

- may bypass the maintenance window
- must not bypass the activity gate
- returns a clear conflict if work is running

## Configuration Model

### Deployment-Level Settings

Keep `BotProperties.update` for deployment concerns only:

- `enabled`
- `updatesPath`

The existing `maxKeptVersions` behavior should stop being the primary retention rule. The new runtime invariant is fixed by product behavior rather than configurable retention count.

### Runtime Settings

Add a new runtime-config section persisted as `update.json`.

Suggested model:

```java
public static class UpdateConfig {
    private Boolean autoEnabled;
    private Integer checkIntervalMinutes;
    private Boolean maintenanceWindowEnabled;
    private String maintenanceWindowStartUtc;
    private String maintenanceWindowEndUtc;
}
```

Defaults:

- `autoEnabled = true`
- `checkIntervalMinutes = 60`
- `maintenanceWindowEnabled = false`
- `maintenanceWindowStartUtc = "00:00"`
- `maintenanceWindowEndUtc = "00:00"`

Semantics:

- `maintenanceWindowEnabled = false` means "any time"
- when enabled, start/end are interpreted in UTC
- overnight windows must be supported, for example `23:00 -> 02:00`

## API Changes

### Existing Endpoints Kept

- `GET /api/system/update/status`
- `POST /api/system/update/check`
- `POST /api/system/update/update-now`

### New Endpoints

- `GET /api/system/update/config`
- `PUT /api/system/update/config`

These endpoints should operate on the runtime-config `update` section rather than forcing the frontend to submit the entire runtime config document.

### Status Payload Extensions

Extend update status with orchestration metadata needed by the UI:

- `autoEnabled`
- `maintenanceWindowEnabled`
- `maintenanceWindowStartUtc`
- `maintenanceWindowEndUtc`
- `serverTimezone`
- `busy`
- `windowOpen`
- `blockedReason`
- `nextEligibleAt`

Suggested blocker values:

- `ACTIVE_SESSION_RUN`
- `QUEUED_SESSION_WORK`
- `AUTO_JOB_RUNNING`

## State Model

Current states are insufficient to explain why a staged update is not progressing.

Add:

- `WAITING_FOR_WINDOW`
- `WAITING_FOR_IDLE`

Intended meaning:

- `STAGED` means staged and ready without an explicit blocker
- `WAITING_FOR_WINDOW` means the update is ready but outside the allowed time window
- `WAITING_FOR_IDLE` means the update is ready but blocked by active work

This allows both API and UI to express update readiness clearly without overloading free-text descriptions.

## UI Design

The existing Updates tab should be extended into two concerns:

### 1. Update Workflow Panel

Continue to show:

- current version
- target version
- workflow stages
- manual actions

Add explicit waiting explanations:

- waiting for maintenance window
- waiting for active work to finish

### 2. Auto Update Settings Panel

Add controls for:

- enable automatic updates
- polling interval
- maintenance mode:
  - any time
  - maintenance window
- start time
- end time

Timezone UX requirements:

- the editor works in the user's local timezone
- the server persists UTC values only
- the UI must explicitly show both:
  - the user's timezone, e.g. `Europe/Berlin`
  - the resulting saved UTC window, e.g. `Saved on server as UTC: 01:00-03:00`

This should be visible at edit time, not only after save.

## Internal Integration Points

### SessionRunCoordinator

Add explicit query methods rather than inspecting private state indirectly.

Suggested options:

- `boolean hasActiveOrQueuedWork()`
- or `Optional<UpdateBlocker> getUpdateBlocker()`

This should cover:

- running inbound
- queued steering messages
- queued follow-up messages

### AutoModeScheduler

Expose a small query surface:

- `boolean isExecuting()`

This should reflect whether a tick or schedule dispatch is currently in progress.

## Startup and Cleanup Flow

Cleanup must run only after the new runtime is confirmed to have started successfully.

Expected startup recovery flow:

1. Launcher starts the jar pointed to by `current.txt`.
2. Backend reaches successful startup.
3. Update subsystem performs recovery:
   - clear obsolete staged marker if no longer needed
   - run cleanup
4. Cleanup keeps at most:
   - the current jar
   - the staged jar, only if a different staged artifact legitimately exists
5. Any other jars are deleted.

This preserves rollback awareness for an actively staged artifact while enforcing the product invariant that only two jars may exist simultaneously.

## Error Handling

### Failure Cases

- release check failure -> `FAILED`, store `lastError`, retry on later auto ticks
- prepare/download failure -> delete temp file, do not write staged marker, set `FAILED`
- blocked by window -> `WAITING_FOR_WINDOW`, not an error
- blocked by work -> `WAITING_FOR_IDLE`, not an error
- manual update while busy -> conflict response with clear reason
- corrupted marker or missing staged jar -> self-heal by clearing invalid marker and returning to a recoverable state

### Non-Goals

- forced update while work is still active
- multi-slot retention beyond `current + staged`
- storing user-local timezone in backend config

## Testing Strategy

### UpdateService Tests

Add coverage for:

- auto update enabled by default
- automatic polling behavior
- waiting for maintenance window
- waiting for idle runtime
- manual update blocked while work is active
- no cleanup during prepare
- recovery from invalid staged marker

### UpdateMaintenanceWindow Tests

Add coverage for:

- disabled window => always open
- standard daytime window
- overnight window
- `nextEligibleAt` calculation

### UpdateRuntimeCleanupService Tests

Add coverage for:

- keep only current jar
- keep current and staged jars
- delete older jars after successful startup
- ignore missing markers safely

### Activity Gate Tests

Add coverage for:

- active session run blocker
- queued work blocker
- auto-mode execution blocker
- fully idle state

### Dashboard Tests

Add coverage for:

- timezone conversion from local time to UTC
- explicit timezone labeling
- waiting-state rendering
- config load/save behavior

## Implementation Notes

- Reuse existing `UpdateService` status model instead of introducing a second update API surface.
- Keep update config in runtime config so it can be changed without rebuilding the deployment.
- Avoid coupling this feature to the generic auto/schedule subsystem; update lifecycle is operational infrastructure, not user automation.
- Favor small helper classes over adding more responsibilities directly to the existing `UpdateService`.

## Open Questions

None at the moment. The approved scope is specific enough to move to an implementation plan.
