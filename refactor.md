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

## Cycle 3

### Findings

- [fixed] `src/main/java/me/golemcore/bot/domain/selfevolving/tactic/LocalEmbeddingBootstrapService.java`
  performed direct OkHttp/Ollama HTTP calls from the domain layer. Moved local
  runtime probing and model pull behavior behind outbound Ollama ports so the
  service now stays focused on bootstrap orchestration.
- [fixed] `src/main/java/me/golemcore/bot/adapter/outbound/embedding/OllamaRuntimeProbeAdapter.java`
  now owns loopback-only Ollama endpoint canonicalization and request building.
  This made the SSRF boundary explicit and addressed the failing GitHub
  `CodeQL` check that flagged runtime URL usage in the previous design.
- [fixed] The first full `./mvnw clean test` after introducing separate Ollama
  probe/model beans failed in `ApplicationSmokeTest` with Spring wiring
  ambiguity (`No qualifying bean ... found 3`). Collapsed that wiring into a
  single composite `OllamaRuntimeApiPort` bean so both probe and model actions
  resolve to one adapter instance cleanly.
- [fixed] `src/test/java/me/golemcore/bot/domain/selfevolving/tactic/LocalEmbeddingBootstrapServiceHttpTest.java`
  was adapter-level HTTP behavior living under domain tests. Removed it and
  moved the coverage into
  `src/test/java/me/golemcore/bot/adapter/outbound/embedding/OllamaRuntimeProbeAdapterTest.java`
  to match the new hexagonal boundary.

### Verification

- [done] Focused verification:
  `./mvnw -Dtest=OllamaRuntimeProbeAdapterTest,LocalEmbeddingBootstrapServiceTest,ManagedLocalOllamaSupervisorTest,ManagedLocalOllamaLifecycleBridgeTest test`
- [done] Focused smoke verification after Spring wiring fix:
  `./mvnw -Dtest=ApplicationSmokeTest,OllamaRuntimeProbeAdapterTest,LocalEmbeddingBootstrapServiceTest test`
- [done] Full verification:
  `./mvnw clean test`
  Result: `Tests run: 3907, Failures: 0, Errors: 0, Skipped: 0`
