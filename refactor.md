# SelfEvolving Refactor Log

## Scope

- Branch: `feat/selfevolving-foundation`
- PR: `#231`
- Constraint: refactor only within files already touched by this PR/branch
- Goal: improve hexagonal boundaries and human readability in `golemcore-bot`

## Cycle 1

### Findings

- [fixed] `src/main/java/me/golemcore/bot/domain/selfevolving/SelfEvolvingProjectionService.java`
  imported inbound web DTOs from `adapter/inbound/web/dto/*`. Moved to
  `src/main/java/me/golemcore/bot/adapter/inbound/web/projection/SelfEvolvingProjectionService.java`.
- [fixed] `src/main/java/me/golemcore/bot/domain/selfevolving/artifact/ArtifactProjectionService.java`
  also imported inbound web DTOs. Moved to
  `src/main/java/me/golemcore/bot/adapter/inbound/web/projection/ArtifactProjectionService.java`.

### Plan

- Move dashboard/web projection services into the inbound web adapter layer.
- Keep controllers depending on an adapter-facing projection facade.
- Re-run focused tests, then full `./mvnw test`, then commit and push.

### Verification

- [done] Focused verification:
  `./mvnw -Dtest=SelfEvolvingProjectionServicePackageTest,ArtifactProjectionServicePackageTest,SelfEvolvingProjectionServiceTest,SelfEvolvingControllerTest,SelfEvolvingControllerArtifactWorkspaceTest,SelfEvolvingControllerTacticSearchTest,SelfEvolvingPromotionControllerTest test`
- [done] Full verification:
  `./mvnw clean test`
  Result: `Tests run: 3910, Failures: 0, Errors: 0, Skipped: 0`

## Cycle 2

### Findings

- [fixed] `src/main/java/me/golemcore/bot/domain/system/HiveRuntimeEventDispatchSystem.java`
  lived in the domain pipeline while depending on outbound Hive publishing and
  adapter DTO serialization details. Moved to
  `src/main/java/me/golemcore/bot/adapter/outbound/hive/HiveRuntimeEventDispatchSystem.java`.
- [fixed] Incremental `./mvnw test` after package moves left stale compiled test
  classes under `target/test-classes`, causing JUnit discovery for an old FQN.
  Verified root cause by inspecting duplicate class files, then switched final
  verification to `./mvnw clean test`.

### Verification

- [done] Focused verification:
  `./mvnw clean -Dtest=HiveRuntimeEventDispatchSystemPackageTest,HiveRuntimeEventDispatchSystemTest,SelfEvolvingProjectionServicePackageTest,ArtifactProjectionServicePackageTest test`
- [done] Full verification:
  `./mvnw clean test`
  Result: `Tests run: 3910, Failures: 0, Errors: 0, Skipped: 0`
