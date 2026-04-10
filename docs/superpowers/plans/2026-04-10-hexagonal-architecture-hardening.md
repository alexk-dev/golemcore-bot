# Hexagonal Architecture Hardening Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Java backend from “package-clean but debt-allowlisted” to a materially cleaner hexagonal architecture by removing framework and low-level IO concerns from the core, splitting mixed-direction transport contracts, and isolating web API contracts from domain models.

**Architecture:** Preserve the dependency direction `adapter -> application -> domain -> port -> adapter`. Move Spring lifecycle/wiring to `adapter` or `infrastructure`, push JSON/YAML/HTTP/filesystem concerns behind outbound ports, split inbound and outbound channel contracts, and keep the core focused on orchestration, policies, and domain decisions.

**Tech Stack:** Spring Boot 4, Java 17, ArchUnit, JUnit 5, Mockito, Jackson

---

## Current review baseline

This plan is based on the following architectural findings from `main`:

- Package direction is mostly healthy: no meaningful `domain -> adapter/infrastructure/plugin/proto` imports were found.
- The backend already has a real `application` layer in several areas (`models`, `settings`, `scheduler`, `skills`, `update`).
- The main remaining debt is **inside the core**:
  - mass use of Spring stereotypes inside `domain`
  - direct `ObjectMapper` / `YAMLFactory` / `Files` usage inside `domain`
  - two application hotspots that still embed adapter work:
    - `application/skills/SkillMarketplaceService`
    - `application/update/UpdateService`
  - mixed-direction transport contract in `ChannelPort`
  - direct HTTP exposure of `RuntimeConfig` through `SettingsController`
  - several oversized core files that attract further architectural debt

---

## Execution strategy

Use **small PR slices**. Do **not** attempt a single big-bang rewrite.

Recommended order:
1. Freeze and tighten architectural guardrails.
2. Clean application hotspots.
3. Clean domain persistence / serialization hotspots.
4. Split transport contracts and move transport-specific behavior outward.
5. Isolate settings web DTOs from domain config.
6. Burn down Spring stereotype debt in batches.
7. Split oversized classes and remove now-obsolete allowlist entries.

---

## Task 1: Tighten architecture guardrails before refactoring

**Files:**
- Modify: `src/test/java/me/golemcore/bot/architecture/HexagonalArchitectureContractTest.java`
- Create: `src/test/java/me/golemcore/bot/architecture/ArchitectureAllowlistConsistencyTest.java`
- Modify: `src/test/resources/architecture/domain-low-level-dependency-allowlist.txt`
- Modify: `src/test/resources/architecture/application-low-level-dependency-allowlist.txt`
- Modify: `src/test/resources/architecture/domain-spring-stereotype-allowlist.txt`

- [x] **Step 1: Write failing tests for stale allowlists and debt growth hygiene**

Add tests that assert:
- every allowlisted class still exists
- allowlist entries are sorted and unique
- domain/application low-level dependency debt can only appear via explicit allowlist entry
- Spring stereotype debt in `domain` cannot silently grow without touching the allowlist

- [x] **Step 2: Run the architecture test slice and verify it fails first**

Run:
```bash
./mvnw -q -Dtest=HexagonalArchitectureContractTest,ArchitectureAllowlistConsistencyTest test
```
Expected: FAIL until the consistency checks are implemented and resources are normalized.

- [x] **Step 3: Implement the consistency tests and normalize allowlists**

Keep the current debt explicit, but make stale allowlist entries fail fast so cleanup work becomes measurable.

- [x] **Step 4: Re-run the architecture test slice**

Run:
```bash
./mvnw -q -Dtest=HexagonalArchitectureContractTest,ArchitectureAllowlistConsistencyTest test
```
Expected: PASS.

---

## Task 2: Extract adapter work out of application hotspots

### 2A. Skill marketplace

**Files:**
- Modify: `src/main/java/me/golemcore/bot/application/skills/SkillMarketplaceService.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/SkillMarketplaceCatalogPort.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/SkillMarketplaceArtifactPort.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/SkillMarketplaceInstallPort.java`
- Create: `src/main/java/me/golemcore/bot/adapter/outbound/skills/RemoteSkillMarketplaceCatalogAdapter.java`
- Create: `src/main/java/me/golemcore/bot/adapter/outbound/skills/LocalSkillMarketplaceCatalogAdapter.java`
- Create: `src/main/java/me/golemcore/bot/adapter/outbound/skills/SkillMarketplaceInstallAdapter.java`
- Modify: `src/main/java/me/golemcore/bot/infrastructure/config/ApplicationLayerConfiguration.java`
- Test: `src/test/java/me/golemcore/bot/application/skills/SkillMarketplaceServiceTest.java`

- [x] **Step 1: Write failing service tests proving the application layer only orchestrates**

Add tests that assert the application service:
- delegates catalog loading to a port
- delegates artifact content loading to a port
- delegates install/write work to a port
- no longer needs to construct `HttpClient`, use `Files`, or parse raw YAML/JSON itself

- [x] **Step 2: Run focused tests and verify red**

Run:
```bash
./mvnw -q -Dtest=SkillMarketplaceServiceTest test
```
Expected: FAIL because the current class still mixes use-case logic with HTTP/filesystem/codec work.

- [x] **Step 3: Introduce marketplace outbound ports and move protocol/filesystem logic into adapters**

The application service should keep:
- validation
- orchestration
- install/update decision logic

Adapters should own:
- GitHub/HTTP access
- local repository scanning
- manifest parsing
- artifact write/delete mechanics

- [x] **Step 4: Re-run focused marketplace tests**

Run:
```bash
./mvnw -q -Dtest=SkillMarketplaceServiceTest test
```
Expected: PASS.

### 2B. Update workflow

**Files:**
- Modify: `src/main/java/me/golemcore/bot/application/update/UpdateService.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/UpdateArtifactStorePort.java`
- Create: `src/main/java/me/golemcore/bot/adapter/outbound/update/FileSystemUpdateArtifactStoreAdapter.java`
- Modify: `src/main/java/me/golemcore/bot/infrastructure/config/CoreLayerConfiguration.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/UpdateServiceTest.java`

- [x] **Step 5: Write failing tests for staged/current marker and artifact-store delegation**

Add tests that assert `UpdateService` delegates:
- marker read/write
- staged/current jar resolution
- temp file move / delete
- checksum target file access

to an outbound artifact store port.

- [x] **Step 6: Run focused tests and verify red**

Run:
```bash
./mvnw -q -Dtest=UpdateServiceTest test
```
Expected: FAIL because `UpdateService` still performs direct filesystem work.

- [x] **Step 7: Introduce `UpdateArtifactStorePort` and move local file handling into an adapter**

Keep `UpdateService` responsible for:
- update state machine
- release selection
- business guards
- restart orchestration

Move into the adapter:
- `Path`/`Files` work
- marker persistence
- temp/staged/current artifact operations
- writable-directory enforcement

- [x] **Step 8: Re-run focused update tests**

Run:
```bash
./mvnw -q -Dtest=UpdateServiceTest test
```
Expected: PASS.

---

## Task 3: Remove persistence and serialization mechanics from domain services

**Files:**
- Modify: `src/main/java/me/golemcore/bot/domain/service/DelayedSessionActionService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/selfevolving/tactic/TacticRecordService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/selfevolving/promotion/PromotionWorkflowStore.java`
- Modify: `src/main/java/me/golemcore/bot/domain/selfevolving/benchmark/LlmJudgeService.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/DelayedActionRegistryPort.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/selfevolving/TacticRecordStorePort.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/selfevolving/PromotionWorkflowStatePort.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/TraceSnapshotCodecPort.java`
- Create adapters under:
  - `src/main/java/me/golemcore/bot/adapter/outbound/storage/`
  - `src/main/java/me/golemcore/bot/adapter/outbound/selfevolving/`
  - `src/main/java/me/golemcore/bot/adapter/outbound/trace/`
- Test:
  - `src/test/java/me/golemcore/bot/domain/service/DelayedActionServiceTest.java`
  - `src/test/java/me/golemcore/bot/domain/selfevolving/tactic/TacticRecordServiceTest.java`
  - `src/test/java/me/golemcore/bot/domain/selfevolving/promotion/PromotionWorkflowStoreTest.java`
  - `src/test/java/me/golemcore/bot/domain/selfevolving/benchmark/LlmJudgeServiceTest.java`

- [x] **Step 1: Write failing tests around port-based persistence/codec delegation**

For each hotspot, add tests asserting the domain service:
- does not construct or own `ObjectMapper`
- does not read/write raw JSON itself
- delegates persistence and serialization details to ports

- [x] **Step 2: Run the focused domain test slice and verify red**

Run:
```bash
./mvnw -q -Dtest=DelayedActionDispatcherTest,DelayedActionPolicyServiceTest,DelayedActionPolicyServiceCapabilityTest,DelayedActionDispatcherTest,DelayedSessionActionServiceTest,TacticRecordServiceTest,PromotionWorkflowStoreTest,LlmJudgeServiceTest test
```
Expected: FAIL until the new ports/adapters exist and the domain services are narrowed.

- [x] **Step 3: Introduce store/codec ports and migrate the domain services**

Keep domain logic responsible for:
- validation
- scheduling policy
- state transitions
- ranking / promotion decisions
- trace/judge orchestration

Move technical concerns out:
- JSON codec selection
- Jackson modules
- storage serialization format
- snapshot byte encoding

- [x] **Step 4: Re-run the focused domain test slice**

Run the same command as Step 2.
Expected: PASS.

---

## Task 4: Remove direct filesystem helpers from the domain core

**Files:**
- Modify: `src/main/java/me/golemcore/bot/domain/service/WorkspacePathService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/ToolArtifactService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/DashboardFileService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/WorkspaceInstructionService.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/WorkspaceFilePort.java`
- Create: `src/main/java/me/golemcore/bot/adapter/outbound/storage/LocalWorkspaceFileAdapter.java`
- Test:
  - `src/test/java/me/golemcore/bot/domain/service/WorkspacePathServiceTest.java`
  - `src/test/java/me/golemcore/bot/domain/service/ToolArtifactServiceTest.java`
  - `src/test/java/me/golemcore/bot/domain/service/WorkspaceInstructionServiceTest.java`
  - `src/test/java/me/golemcore/bot/domain/service/UpdateRuntimeCleanupServiceTest.java`

- [x] **Step 1: Write failing tests that pin the boundary between path policy and file execution**

Add tests that distinguish:
- path normalization / traversal rules in domain
- actual file existence / directory creation / content probing in an outbound port

- [x] **Step 2: Run the focused workspace test slice and verify red**

Run:
```bash
./mvnw -q -Dtest=WorkspacePathServiceTest,ToolArtifactServiceTest,WorkspaceInstructionServiceTest,UpdateRuntimeCleanupServiceTest test
```
Expected: FAIL until filesystem operations move behind a port.

- [x] **Step 3: Introduce `WorkspaceFilePort` and move `Files.*` calls into the adapter**

The domain may still own path safety and policy decisions, but the adapter should own the actual local filesystem API.

- [x] **Step 4: Re-run the focused workspace test slice**

Run the same command as Step 2.
Expected: PASS.

---

## Task 5: Split mixed-direction transport contracts and move transport shaping out of domain systems

**Files:**
- Modify: `src/main/java/me/golemcore/bot/port/inbound/ChannelPort.java`
- Create: `src/main/java/me/golemcore/bot/port/inbound/InboundChannelPort.java`
- Create: `src/main/java/me/golemcore/bot/port/outbound/ChannelDeliveryPort.java`
- Modify: `src/main/java/me/golemcore/bot/port/outbound/ChannelRuntimePort.java`
- Modify: `src/main/java/me/golemcore/bot/domain/system/ResponseRoutingSystem.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/SessionRunCoordinator.java`
- Create: `src/main/java/me/golemcore/bot/application/channel/OutgoingResponseDeliveryService.java`
- Create adapter-side collaborators for web/webhook payload shaping under `adapter/outbound/channel/`
- Test:
  - `src/test/java/me/golemcore/bot/domain/system/ResponseRoutingSystemTest.java`
  - `src/test/java/me/golemcore/bot/domain/service/SessionRunCoordinatorTest.java`
  - `src/test/java/me/golemcore/bot/port/inbound/ChannelPortTest.java`

- [x] **Step 1: Write failing tests that pin the new transport direction split**

Add tests asserting:
- the inbound listener contract is separate from the outbound delivery contract
- the core no longer requires one interface that mixes `start()/onMessage()` with `sendMessage()/sendDocument()`
- `ResponseRoutingSystem` delegates web/webhook transport shaping to a collaborator instead of building structured transport payloads itself

- [x] **Step 2: Run focused routing/coordinator tests and verify red**

Run:
```bash
./mvnw -q -Dtest=ResponseRoutingSystemTest,ResponseRoutingSystemOutgoingResponseTest,SessionRunCoordinatorTest,ChannelPortTest test
```
Expected: FAIL because the transport contract is still mixed.

- [x] **Step 3: Introduce separate inbound and outbound channel contracts**

Recommended split:
- inbound: lifecycle + message subscription
- outbound: text/voice/document/runtime-event delivery

Then move:
- web payload shaping
- webhook cross-channel delivery shaping
- attachment metadata formatting

out of `ResponseRoutingSystem` into an adapter/application collaborator.

- [x] **Step 4: Re-run focused routing/coordinator tests**

Run the same command as Step 2.
Expected: PASS.

---

## Task 6: Isolate settings HTTP contracts from `RuntimeConfig`

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
- Create DTOs under: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/settings/`
- Create mapper: `src/main/java/me/golemcore/bot/adapter/inbound/web/mapper/RuntimeSettingsWebMapper.java`
- Modify: `src/main/java/me/golemcore/bot/application/settings/RuntimeSettingsFacade.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java`

- [x] **Step 1: Write failing controller tests for DTO-based settings contracts**

Add tests that assert:
- HTTP requests/responses use dedicated web DTOs
- `SettingsController` does not expose raw `RuntimeConfig` directly
- web-specific normalization and mapping live in a mapper, not in the controller/domain model

- [x] **Step 2: Run focused settings controller tests and verify red**

Run:
```bash
./mvnw -q -Dtest=SettingsControllerTest test
```
Expected: FAIL because the controller still accepts/returns `RuntimeConfig` in several endpoints.

- [ ] **Step 3: Introduce settings DTOs and mapper layer**

Keep the application facade and domain model transport-agnostic. Let the controller map:
- HTTP DTO -> application command/input DTO
- application/domain result -> HTTP DTO

- [ ] **Step 4: Re-run focused settings controller tests**

Run:
```bash
./mvnw -q -Dtest=SettingsControllerTest test
```
Expected: PASS.

---

## Task 7: Burn down Spring stereotype usage in the core by batch

**Files:**
- Modify: `src/main/java/me/golemcore/bot/infrastructure/config/ApplicationLayerConfiguration.java`
- Modify: `src/main/java/me/golemcore/bot/infrastructure/config/CoreLayerConfiguration.java`
- Create additional configuration classes under `src/main/java/me/golemcore/bot/infrastructure/config/`
- Modify selected domain/application classes to remove `@Service` / `@Component`
- Modify: `src/test/resources/architecture/domain-spring-stereotype-allowlist.txt`
- Test: `src/test/java/me/golemcore/bot/architecture/HexagonalArchitectureContractTest.java`

- [ ] **Step 1: Pick one bounded cluster and write failing wiring tests first**

Recommended order:
1. `domain/context/*`
2. `domain/memory/*`
3. `domain/selfevolving/*`
4. remaining orchestration services/systems

For each batch, add or update Spring wiring tests so bean construction remains covered while stereotypes are removed.

- [ ] **Step 2: Move bean registration into infrastructure configuration**

Remove stereotypes from the batch and register them explicitly via constructor-based `@Bean` methods.

- [ ] **Step 3: Shrink the domain stereotype allowlist after each batch**

Do not defer allowlist cleanup. Remove entries immediately when a batch is migrated.

- [ ] **Step 4: Re-run the architecture tests after each batch**

Run:
```bash
./mvnw -q -Dtest=HexagonalArchitectureContractTest,ApplicationLayerConfigurationTest,CoreLayerConfigurationTest test
```
Expected: PASS.

---

## Task 8: Split oversized core files after boundaries are cleaned

**Files:**
- Modify / split:
  - `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
  - `src/main/java/me/golemcore/bot/domain/system/ResponseRoutingSystem.java`
  - `src/main/java/me/golemcore/bot/domain/selfevolving/benchmark/LlmJudgeService.java`
  - `src/main/java/me/golemcore/bot/domain/service/SessionRunCoordinator.java`
  - `src/main/java/me/golemcore/bot/application/skills/SkillMarketplaceService.java`
  - `src/main/java/me/golemcore/bot/application/update/UpdateService.java`
- Modify:
  - `src/test/resources/architecture/large-domain-file-allowlist.txt`
  - `src/test/resources/architecture/large-production-file-allowlist.txt`
- Test:
  - `src/test/java/me/golemcore/bot/architecture/CodeSizeContractTest.java`

- [ ] **Step 1: Write failing tests or assertions around the extracted collaborators when splitting**

Do not split purely mechanically. Extract cohesive collaborators such as:
- routing payload builder / attachment delivery helper
- judge prompt builder / retry executor / verdict merger
- runtime config normalizer sections
- session queue policy / hive interruption helper

- [ ] **Step 2: Remove allowlist entries only when the file is actually reduced below threshold**

Keep the code size contract honest.

- [ ] **Step 3: Re-run size and architecture contracts**

Run:
```bash
./mvnw -q -Dtest=CodeSizeContractTest,HexagonalArchitectureContractTest test
```
Expected: PASS, with reduced allowlists.

---

## Task 9: Full verification and merge readiness

**Files:**
- Modify: `docs/superpowers/plans/2026-04-10-hexagonal-architecture-hardening.md`

- [ ] **Step 1: Run focused architecture verification**

Run:
```bash
./mvnw -q -Dtest=HexagonalArchitectureContractTest,ArchitectureAllowlistConsistencyTest,CodeSizeContractTest test
```
Expected: PASS.

- [ ] **Step 2: Run focused application/domain regression suites for touched areas**

Run:
```bash
./mvnw -q -Dtest=SettingsControllerTest,ModelsControllerTest,SkillsControllerTest,SchedulerControllerTest,SessionRunCoordinatorTest,ResponseRoutingSystemTest,UpdateServiceTest,SkillMarketplaceServiceTest,TacticRecordServiceTest,PromotionWorkflowStoreTest,LlmJudgeServiceTest test
```
Expected: PASS.

- [ ] **Step 3: Run full strict verification**

Run:
```bash
./mvnw clean verify -P strict
```
Expected: PASS.

- [ ] **Step 4: Run patch hygiene**

Run:
```bash
git diff --check
```
Expected: no output.

- [ ] **Step 5: Prepare a PR as a stack, not a monolith**

Recommended PR split:
1. Architecture guardrails
2. Application hotspot extraction
3. Domain store/codec extraction
4. Channel contract split
5. Settings DTO boundary
6. Stereotype cleanup batches
7. Large-file cleanup and final allowlist shrink

---

## Success criteria

This plan is complete when all of the following are true:

- `domain` no longer grows framework/runtime coupling through silent allowlist expansion.
- `application` no longer contains raw HTTP/filesystem/codec adapters in disguise.
- `domain` no longer directly owns JSON/YAML serialization or local filesystem execution.
- inbound and outbound transport contracts are clearly separated.
- web settings APIs no longer expose raw `RuntimeConfig` as their primary HTTP contract.
- architecture allowlists are smaller and actively enforced.
- the largest core hotspots are reduced enough that the size allowlists can shrink.
