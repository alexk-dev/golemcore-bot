# 2026-04-16 — Container image update vs persisted `current.txt`

**Goal:** make a freshly pulled container image boot the newer bundled runtime when the persisted auto-update marker still points to an older jar.

## Current behavior

1. The Docker image entrypoint starts `RuntimeLauncher` from the **image classpath**.
2. `RuntimeLauncher` checks `${UPDATE_PATH}/current.txt`.
3. If the marker exists and the jar exists under `${UPDATE_PATH}/jars`, the launcher always executes `java -jar <that-jar>`.
4. `${UPDATE_PATH}` lives under `${STORAGE_PATH}/updates` by default, so it usually sits on a **persistent volume**.
5. When operators pull a newer image, the old `current.txt` and jar survive the container replacement.
6. On the next boot, the launcher still prefers the old persisted jar, so the runtime stays on the old version.

## Root cause

The launcher currently treats `current.txt` as an unconditional runtime override.

Relevant places:

- `pom.xml` — container entrypoint starts `me.golemcore.bot.launcher.RuntimeLauncher`
- `src/main/java/me/golemcore/bot/launcher/RuntimeLauncher.java`
  - `resolveLaunchCommand()`
  - `resolveCurrentJar()`
- `src/main/java/me/golemcore/bot/adapter/outbound/update/FileSystemUpdateArtifactStoreAdapter.java`
  - persists `current.txt` under the updates directory
- `src/main/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupService.java`
  - keeps the current/staged jars after startup, so the stale current jar remains available forever

## Why image refresh does not win today

Even though the container image is newer, the launcher never compares:

- bundled image version
- persisted `current.txt` jar version

So the launcher has no rule that says:

> "If the image is newer than the persisted jar, boot the image instead."

## Constraints

Any fix should preserve these behaviors:

1. **Do not break self-update:** if auto-update downloaded a jar newer than the bundled image, the launcher should still use that newer jar.
2. **Do not force downgrade:** if an older image is started against a volume that already contains a newer jar, the newer jar should keep winning.
3. **Do not require Spring startup for the decision:** the launcher must decide before the real app starts.
4. **Keep persisted update workflow intact:** `staged.txt` should remain usable for normal update/apply flows.

## Proposed direction

### 1. Teach the launcher to compare bundled image version vs persisted current jar version

At boot time the launcher already runs from the bundled image classpath, so it can read the image build version directly from classpath metadata (for example `META-INF/build-info.properties`).

Proposed rule:

- if `current.txt` is missing or invalid → boot bundled image runtime
- if `current.txt` points to a jar whose version is **newer** than the bundled image → boot that jar
- if bundled image version is **newer** than the persisted jar → ignore `current.txt` and boot bundled image runtime
- if versions are equal → keep current jar behavior for minimal change

This gives a simple priority rule:

> boot whichever runtime is newer

### 2. Clean up stale markers after a successful newer-image startup

If the launcher booted the image because the image is newer than `current.txt`, the runtime should remove stale persisted state after startup:

- delete `current.txt` when it points to an older jar than the running image
- delete the stale old jar if it is no longer retained
- keep `staged.txt` if it points to a newer candidate that has not been applied yet

Without this cleanup, the launcher would still need to re-decide on every restart and old jars would accumulate longer than necessary.

### 3. Reuse one version parser/comparator

The update flow already has version parsing/comparison logic in `UpdateVersionSupport`, but it is package-private under `application.update`.

Implementation should extract or promote a small shared utility so both:

- `RuntimeLauncher`
- update services / cleanup services

use the same version semantics.

## TDD slice proposal

### Red/green step 1

Add launcher tests:

- `shouldLaunchBundledRuntimeWhenImageVersionIsNewerThanCurrentMarker()`
- `shouldKeepUpdatedJarWhenCurrentMarkerVersionIsNewerThanImageVersion()`

### Red/green step 2

Add cleanup tests:

- `shouldDeleteStaleCurrentMarkerWhenRunningImageIsNewer()`
- `shouldKeepStagedJarWhenItIsStillNewerThanRunningImage()`

## Expected outcome after the fix

Scenario:

- persisted volume contains `current.txt -> bot-0.4.1.jar`
- operator pulls image `0.4.2`
- container restarts

Expected result:

1. launcher detects image `0.4.2` > current jar `0.4.1`
2. launcher boots bundled image runtime `0.4.2`
3. startup cleanup removes stale `current.txt` / old jar
4. dashboard and `/api/system/health` report `0.4.2`

## Open policy question

**Equal version policy:**

- safest minimal-change option: keep using the persisted jar when versions are equal
- alternative option: prefer bundled image on equality to guarantee that an image refresh always wins even for same-version rebuilds

Recommended for the first fix: **keep jar on equality** and only override when the image is strictly newer.
