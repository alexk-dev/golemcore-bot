# OTel Session Tracing Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OTel-compatible internal request tracing with session-backed protobuf persistence, configurable payload snapshots and budgets, end-to-end propagation across product flows, and first-party dashboard settings and trace analysis tools.

**Architecture:** Introduce a canonical trace core in domain code, store trace trees and compressed snapshots directly in `session.pb`, propagate `traceId/spanId` through ingress, loop, LLM, tool, storage, and outbound boundaries, and expose the resulting trace store through runtime config, session APIs, and a dashboard trace explorer. Keep tracing enabled by default, keep payload snapshots disabled by default, and enforce a per-session compressed snapshot budget with oldest-snapshot-first eviction.

**Tech Stack:** Spring Boot WebFlux, Java 17, Protobuf, Maven, React 18, TypeScript 5, React Query, React Bootstrap, Vite, Zstd JNI.

---

## File Map

### Backend runtime config and persistence

- Modify: `pom.xml`
  - add compression dependency for snapshot storage (`zstd-jni`)
- Modify: `src/main/proto/session.proto`
  - add `TraceRecord`, `SpanRecord`, `SpanEventRecord`, `SnapshotRecord`, `TraceStorageStats`
- Modify: `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
  - add `TracingConfig` section and include it in the root runtime config
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
  - default and persist tracing config, expose getters, normalize limits
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
  - validate and persist tracing config updates
- Modify: `src/main/java/me/golemcore/bot/domain/service/SessionProtoMapper.java`
  - map domain trace records to protobuf and back
- Modify: `src/main/java/me/golemcore/bot/domain/service/SessionService.java`
  - ensure session load/save keeps traces and storage stats intact

### Backend trace core

- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceContext.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceSpanKind.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceStatusCode.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceSnapshot.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceEventRecord.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceSpanRecord.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceRecord.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceStorageBudget.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceService.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceSnapshotCompressionService.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceBudgetService.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceMdcSupport.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceNamingSupport.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/AgentContext.java`
  - hold active trace identifiers and accumulated trace data for the turn
- Modify: `src/main/java/me/golemcore/bot/domain/model/ContextAttributes.java`
  - canonical keys for trace ids, parent ids, root kind, truncation metadata
- Modify: `src/main/resources/logback-spring.xml`
  - emit `trace`, `span`, `session`, `conv`, `run`

### Backend ingress and runtime instrumentation

- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandler.java`
  - create root trace for websocket inbound messages
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/WebChannelAdapter.java`
  - carry trace metadata into outbound websocket payload delivery spans
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/webhook/WebhookController.java`
  - create root trace for webhook agent/wake requests
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/webhook/WebhookChannelAdapter.java`
  - trace pending run completion and callback delivery
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java`
  - root/child spans for explicit slash-command execution
- Modify: `src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java`
  - root traces for scheduled automation ticks
- Modify: `src/main/java/me/golemcore/bot/domain/service/DelayedSessionActionScheduler.java`
  - root traces for delayed session wakeups
- Modify: `src/main/java/me/golemcore/bot/domain/loop/AgentLoop.java`
  - wrap loop lifecycle, per-system execution, retry, compaction, finalization in spans
- Modify: `src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java`
  - tool loop spans, tool call spans, trace-aware retries and failures
- Modify: `src/main/java/me/golemcore/bot/domain/system/ResponseRoutingSystem.java`
  - outbound response routing spans and trace propagation
- Modify: `src/main/java/me/golemcore/bot/adapter/outbound/llm/Langchain4jAdapter.java`
  - request-preparation span, provider-call span, parsing event/span, optional snapshots
- Modify: `src/main/java/me/golemcore/bot/domain/model/LlmRequest.java`
  - explicit trace metadata and snapshot policy

### Backend session trace APIs

- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SessionsController.java`
  - add trace summary/detail/export endpoints
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSummaryDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSpanDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSnapshotDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceStorageStatsDto.java`

### Dashboard runtime config and trace explorer

- Modify: `dashboard/src/api/settings.ts`
  - add `TracingConfig` and runtime config serialization
- Modify: `dashboard/src/pages/settings/settingsCatalog.ts`
  - register a dedicated tracing section in Runtime
- Modify: `dashboard/src/pages/SettingsPage.tsx`
  - mount the new tracing settings tab
- Create: `dashboard/src/pages/settings/TracingTab.tsx`
  - tracing toggles, budget controls, warning copy
- Modify: `dashboard/src/api/sessions.ts`
  - session trace API types and fetchers
- Modify: `dashboard/src/hooks/useSessions.ts`
  - queries for trace summaries and trace detail/export
- Modify: `dashboard/src/pages/SessionsPage.tsx`
  - host trace explorer entry points
- Create: `dashboard/src/components/sessions/SessionTraceExplorer.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceTree.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceTimeline.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceSnapshotViewer.tsx`
- Create: `dashboard/src/lib/traceFormat.ts`
  - formatting, duration math, tree shaping, truncation badges

### Tests

- Modify: `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/service/SessionProtoMapperTest.java`
- Create: `src/test/java/me/golemcore/bot/domain/service/TraceServiceTest.java`
- Create: `src/test/java/me/golemcore/bot/domain/service/TraceBudgetServiceTest.java`
- Create: `src/test/java/me/golemcore/bot/domain/service/TraceSnapshotCompressionServiceTest.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SessionsControllerTest.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandlerTest.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/inbound/webhook/WebhookControllerTest.java`
- Modify: `src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/service/DelayedSessionActionSchedulerTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/loop/AgentLoopTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystemTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/system/ResponseRoutingSystemTest.java`
- Modify: `src/test/java/me/golemcore/bot/adapter/outbound/llm/Langchain4jAdapterTest.java`
- Create: `dashboard/src/pages/settings/TracingTab.test.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceExplorer.test.tsx`
- Create: `dashboard/src/lib/traceFormat.test.ts`
- Modify: `dashboard/src/pages/settings/settingsCatalogSearch.test.ts`

## Task 1: Add tracing runtime config and protobuf schema

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/proto/session.proto`
- Modify: `src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java`

- [ ] **Step 1: Write failing backend config tests**

```java
@Test
void shouldDefaultTracingEnabledAndPayloadSnapshotsDisabled() {
    RuntimeConfig config = runtimeConfigService.getRuntimeConfig();
    assertTrue(config.getTracing().getEnabled());
    assertFalse(config.getTracing().getPayloadSnapshotsEnabled());
    assertEquals(128, config.getTracing().getSessionTraceBudgetMb());
}

@Test
void shouldRejectTracingBudgetBelowOneMegabyte() {
    RuntimeConfig.TracingConfig tracing = new RuntimeConfig.TracingConfig();
    tracing.setEnabled(true);
    tracing.setSessionTraceBudgetMb(0);
    assertThrows(IllegalArgumentException.class, () -> controller.updateTracingConfig(tracing));
}
```

- [ ] **Step 2: Run targeted backend tests to verify they fail**

Run: `./mvnw -q -Dtest=RuntimeConfigServiceTest,SettingsControllerTest test`

Expected: FAIL with missing `TracingConfig` fields or missing controller normalization.

- [ ] **Step 3: Add tracing config model and defaults**

```java
public static class TracingConfig {
    private Boolean enabled;
    private Boolean payloadSnapshotsEnabled;
    private Integer sessionTraceBudgetMb;
    private Integer maxSnapshotSizeKb;
    private Integer maxSnapshotsPerSpan;
    private Integer maxTracesPerSession;
    private Boolean captureInboundPayloads;
    private Boolean captureOutboundPayloads;
    private Boolean captureToolPayloads;
    private Boolean captureLlmPayloads;
}
```

- [ ] **Step 4: Extend runtime config service and settings validation**

Run:
- `./mvnw -q -Dtest=RuntimeConfigServiceTest,SettingsControllerTest test`

Expected: PASS

- [ ] **Step 5: Add protobuf trace messages and compression dependency**

Implement:
- `TraceRecord`, `SpanRecord`, `SpanEventRecord`, `SnapshotRecord`, `TraceStorageStats`
- `compressed_payload` bytes field with `encoding`
- `zstd-jni` in `pom.xml`

- [ ] **Step 6: Regenerate protobuf classes and compile**

Run: `./mvnw -q -DskipTests compile`

Expected: PASS with generated protobuf sources available to Java code.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/proto/session.proto \
  src/main/java/me/golemcore/bot/domain/model/RuntimeConfig.java \
  src/main/java/me/golemcore/bot/domain/service/RuntimeConfigService.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsController.java \
  src/test/java/me/golemcore/bot/domain/service/RuntimeConfigServiceTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SettingsControllerTest.java
git commit -m "feat(tracing): add tracing config and protobuf schema"
```

## Task 2: Build the trace core, compression, and session mapping

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceContext.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceSpanKind.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceStatusCode.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceSnapshot.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceEventRecord.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceSpanRecord.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceRecord.java`
- Create: `src/main/java/me/golemcore/bot/domain/model/trace/TraceStorageBudget.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceService.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceSnapshotCompressionService.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceBudgetService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/AgentContext.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/ContextAttributes.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/SessionProtoMapper.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/SessionService.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/SessionProtoMapperTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/TraceServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/TraceBudgetServiceTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/TraceSnapshotCompressionServiceTest.java`

- [ ] **Step 1: Write failing mapper, budget, and compression tests**

```java
@Test
void shouldRoundTripTraceRecordsThroughSessionProto() { }

@Test
void shouldEvictOldestSnapshotsWhenBudgetExceeded() { }

@Test
void shouldCompressAndRestoreSnapshotPayload() { }
```

- [ ] **Step 2: Run targeted tests to verify they fail**

Run: `./mvnw -q -Dtest=SessionProtoMapperTest,TraceServiceTest,TraceBudgetServiceTest,TraceSnapshotCompressionServiceTest test`

Expected: FAIL because trace domain services and mapper support do not exist yet.

- [ ] **Step 3: Implement trace domain records and service skeletons**

```java
public record TraceContext(String traceId, String spanId, String parentSpanId, String rootKind) {}
public enum TraceSpanKind { INGRESS, INTERNAL, LLM, TOOL, STORAGE, OUTBOUND }
public enum TraceStatusCode { OK, ERROR }
```

- [ ] **Step 4: Implement compression and budget enforcement**

Implement:
- zstd compression/decompression service
- compressed-size accounting
- oldest-snapshot-first eviction
- trace/session truncation markers

- [ ] **Step 5: Extend `AgentContext`, `ContextAttributes`, and session mapping**

Implement:
- canonical trace attribute keys
- active trace carrier on `AgentContext`
- `SessionProtoMapper` round-trip for trace trees and storage stats

- [ ] **Step 6: Run targeted tests to verify they pass**

Run: `./mvnw -q -Dtest=SessionProtoMapperTest,TraceServiceTest,TraceBudgetServiceTest,TraceSnapshotCompressionServiceTest test`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/me/golemcore/bot/domain/model/trace \
  src/main/java/me/golemcore/bot/domain/service/TraceService.java \
  src/main/java/me/golemcore/bot/domain/service/TraceSnapshotCompressionService.java \
  src/main/java/me/golemcore/bot/domain/service/TraceBudgetService.java \
  src/main/java/me/golemcore/bot/domain/model/AgentContext.java \
  src/main/java/me/golemcore/bot/domain/model/ContextAttributes.java \
  src/main/java/me/golemcore/bot/domain/service/SessionProtoMapper.java \
  src/main/java/me/golemcore/bot/domain/service/SessionService.java \
  src/test/java/me/golemcore/bot/domain/service/SessionProtoMapperTest.java \
  src/test/java/me/golemcore/bot/domain/service/TraceServiceTest.java \
  src/test/java/me/golemcore/bot/domain/service/TraceBudgetServiceTest.java \
  src/test/java/me/golemcore/bot/domain/service/TraceSnapshotCompressionServiceTest.java
git commit -m "feat(tracing): add trace core and session mapping"
```

## Task 3: Add root trace creation, propagation, and MDC correlation

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceMdcSupport.java`
- Create: `src/main/java/me/golemcore/bot/domain/service/TraceNamingSupport.java`
- Modify: `src/main/resources/logback-spring.xml`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandler.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/webhook/WebhookController.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java`
- Modify: `src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/DelayedSessionActionScheduler.java`
- Modify: `src/main/java/me/golemcore/bot/domain/model/LlmRequest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandlerTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/webhook/WebhookControllerTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/command/CommandRouterTest.java`
- Test: `src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/DelayedSessionActionSchedulerTest.java`

- [ ] **Step 1: Write failing ingress propagation tests**

```java
@Test
void websocketInboundShouldCreateRootTrace() { }

@Test
void webhookAgentRequestShouldStoreTraceContextOnLlmRequest() { }

@Test
void autoModeTickShouldCreateTraceEvenWithoutPayloadSnapshots() { }
```

- [ ] **Step 2: Run targeted tests to verify they fail**

Run: `./mvnw -q -Dtest=WebSocketChatHandlerTest,WebhookControllerTest,CommandRouterTest,AutoModeSchedulerTest,DelayedSessionActionSchedulerTest test`

Expected: FAIL because root trace creation and MDC propagation are absent.

- [ ] **Step 3: Implement MDC helper and standardized log keys**

```java
traceMdcSupport.withContext(traceContext, () -> {
    log.info("[Trace] Started {}", spanName);
});
```

- [ ] **Step 4: Create root traces in ingress and scheduler entry points**

Implement:
- websocket inbound message root spans
- webhook `/agent` and wake root spans
- command execution root/child spans
- auto scheduler and delayed wake roots

- [ ] **Step 5: Propagate trace context into `LlmRequest` and agent context**

Run:
- `./mvnw -q -Dtest=WebSocketChatHandlerTest,WebhookControllerTest,CommandRouterTest,AutoModeSchedulerTest,DelayedSessionActionSchedulerTest test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/logback-spring.xml \
  src/main/java/me/golemcore/bot/domain/service/TraceMdcSupport.java \
  src/main/java/me/golemcore/bot/domain/service/TraceNamingSupport.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandler.java \
  src/main/java/me/golemcore/bot/adapter/inbound/webhook/WebhookController.java \
  src/main/java/me/golemcore/bot/adapter/inbound/command/CommandRouter.java \
  src/main/java/me/golemcore/bot/auto/AutoModeScheduler.java \
  src/main/java/me/golemcore/bot/domain/service/DelayedSessionActionScheduler.java \
  src/main/java/me/golemcore/bot/domain/model/LlmRequest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/WebSocketChatHandlerTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/webhook/WebhookControllerTest.java \
  src/test/java/me/golemcore/bot/adapter/inbound/command/CommandRouterTest.java \
  src/test/java/me/golemcore/bot/auto/AutoModeSchedulerTest.java \
  src/test/java/me/golemcore/bot/domain/service/DelayedSessionActionSchedulerTest.java
git commit -m "feat(tracing): add ingress trace propagation"
```

## Task 4: Instrument loop, LLM, tools, storage, outbound, and snapshots

**Files:**
- Modify: `src/main/java/me/golemcore/bot/domain/loop/AgentLoop.java`
- Modify: `src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java`
- Modify: `src/main/java/me/golemcore/bot/domain/system/ResponseRoutingSystem.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/WebChannelAdapter.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/webhook/WebhookChannelAdapter.java`
- Modify: `src/main/java/me/golemcore/bot/adapter/outbound/llm/Langchain4jAdapter.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/RuntimeEventService.java`
- Test: `src/test/java/me/golemcore/bot/domain/loop/AgentLoopTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystemTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/system/ResponseRoutingSystemTest.java`
- Test: `src/test/java/me/golemcore/bot/adapter/outbound/llm/Langchain4jAdapterTest.java`
- Test: `src/test/java/me/golemcore/bot/domain/service/RuntimeEventServiceTest.java`

- [ ] **Step 1: Write failing runtime instrumentation tests**

```java
@Test
void agentLoopShouldRecordSystemSpansAndRetryEvents() { }

@Test
void llmCallShouldCaptureSnapshotsOnlyWhenEnabled() { }

@Test
void toolLoopShouldRecordToolInputOutputSnapshotsWithinBudget() { }

@Test
void outboundRoutingShouldCreateDeliverySpans() { }
```

- [ ] **Step 2: Run targeted runtime tests to verify they fail**

Run: `./mvnw -q -Dtest=AgentLoopTest,DefaultToolLoopSystemTest,ResponseRoutingSystemTest,Langchain4jAdapterTest,RuntimeEventServiceTest test`

Expected: FAIL because spans, events, and snapshot policies are not yet wired.

- [ ] **Step 3: Instrument agent loop and tool loop spans**

Implement:
- per-system spans
- retry and compaction events
- tool execution spans with failure kind attributes

- [ ] **Step 4: Instrument LLM and outbound boundaries**

Implement:
- LLM request preparation and provider call spans
- optional normalized request/response snapshots
- websocket, webhook callback, and other outbound spans

- [ ] **Step 5: Keep `RuntimeEvent` working while adding trace-backed context**

Implement:
- preserve existing runtime event behavior
- add trace identifiers into runtime event payload metadata where useful

- [ ] **Step 6: Run targeted runtime tests to verify they pass**

Run: `./mvnw -q -Dtest=AgentLoopTest,DefaultToolLoopSystemTest,ResponseRoutingSystemTest,Langchain4jAdapterTest,RuntimeEventServiceTest test`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/me/golemcore/bot/domain/loop/AgentLoop.java \
  src/main/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystem.java \
  src/main/java/me/golemcore/bot/domain/system/ResponseRoutingSystem.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/WebChannelAdapter.java \
  src/main/java/me/golemcore/bot/adapter/inbound/webhook/WebhookChannelAdapter.java \
  src/main/java/me/golemcore/bot/adapter/outbound/llm/Langchain4jAdapter.java \
  src/main/java/me/golemcore/bot/domain/service/RuntimeEventService.java \
  src/test/java/me/golemcore/bot/domain/loop/AgentLoopTest.java \
  src/test/java/me/golemcore/bot/domain/system/toolloop/DefaultToolLoopSystemTest.java \
  src/test/java/me/golemcore/bot/domain/system/ResponseRoutingSystemTest.java \
  src/test/java/me/golemcore/bot/adapter/outbound/llm/Langchain4jAdapterTest.java \
  src/test/java/me/golemcore/bot/domain/service/RuntimeEventServiceTest.java
git commit -m "feat(tracing): instrument loop llm tools and outbound"
```

## Task 5: Expose trace APIs for sessions and export

**Files:**
- Modify: `src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SessionsController.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSummaryDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSpanDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSnapshotDto.java`
- Create: `src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceStorageStatsDto.java`
- Test: `src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SessionsControllerTest.java`

- [ ] **Step 1: Write failing session trace API tests**

```java
@Test
void getSessionTraceSummariesShouldReturnTraceListAndStorageStats() { }

@Test
void getSessionTraceShouldReturnSpanTreeAndSnapshots() { }

@Test
void exportTraceShouldReturnOtelCompatibleJson() { }
```

- [ ] **Step 2: Run controller tests to verify they fail**

Run: `./mvnw -q -Dtest=SessionsControllerTest test`

Expected: FAIL because trace endpoints and DTOs do not exist.

- [ ] **Step 3: Add session trace summary/detail/export endpoints**

Suggested routes:
- `GET /api/sessions/{id}/traces`
- `GET /api/sessions/{id}/traces/{traceId}`
- `GET /api/sessions/{id}/traces/{traceId}/export`

- [ ] **Step 4: Add DTO mapping with snapshot metadata**

Include:
- duration
- status
- span kind
- truncated flags
- compressed vs original size

- [ ] **Step 5: Run controller tests to verify they pass**

Run: `./mvnw -q -Dtest=SessionsControllerTest test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/golemcore/bot/adapter/inbound/web/controller/SessionsController.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSummaryDto.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceDto.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSpanDto.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceSnapshotDto.java \
  src/main/java/me/golemcore/bot/adapter/inbound/web/dto/SessionTraceStorageStatsDto.java \
  src/test/java/me/golemcore/bot/adapter/inbound/web/controller/SessionsControllerTest.java
git commit -m "feat(tracing): add session trace APIs"
```

## Task 6: Add dashboard tracing settings and session trace explorer

**Files:**
- Modify: `dashboard/src/api/settings.ts`
- Modify: `dashboard/src/pages/settings/settingsCatalog.ts`
- Modify: `dashboard/src/pages/SettingsPage.tsx`
- Create: `dashboard/src/pages/settings/TracingTab.tsx`
- Modify: `dashboard/src/api/sessions.ts`
- Modify: `dashboard/src/hooks/useSessions.ts`
- Modify: `dashboard/src/pages/SessionsPage.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceExplorer.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceTree.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceTimeline.tsx`
- Create: `dashboard/src/components/sessions/SessionTraceSnapshotViewer.tsx`
- Create: `dashboard/src/lib/traceFormat.ts`
- Test: `dashboard/src/pages/settings/TracingTab.test.tsx`
- Test: `dashboard/src/components/sessions/SessionTraceExplorer.test.tsx`
- Test: `dashboard/src/lib/traceFormat.test.ts`
- Test: `dashboard/src/pages/settings/settingsCatalogSearch.test.ts`

- [ ] **Step 1: Write failing dashboard tests**

```tsx
it('renders tracing settings with safe defaults', () => { /* enabled on, snapshots off */ });
it('shows trace tree and timeline for a selected session trace', () => { /* explorer */ });
it('adds tracing to the settings catalog search index', () => { /* tracing section visible */ });
```

- [ ] **Step 2: Run targeted dashboard tests to verify they fail**

Run:
- `./node/node ./node/node_modules/npm/bin/npm-cli.js test -- src/pages/settings/TracingTab.test.tsx src/components/sessions/SessionTraceExplorer.test.tsx src/lib/traceFormat.test.ts src/pages/settings/settingsCatalogSearch.test.ts`

Expected: FAIL because tracing tab and trace explorer do not exist.

- [ ] **Step 3: Add tracing settings UI**

Implement:
- dedicated Runtime catalog section
- toggles for tracing and payload snapshots
- numeric inputs for budget and limits
- warnings about sensitive data retention and session growth

- [ ] **Step 4: Add session trace explorer**

Implement:
- modal or panel inside `SessionsPage`
- trace list
- span tree
- timeline
- snapshot viewer
- export action

- [ ] **Step 5: Add client-side formatting helpers and query hooks**

Run:
- `./node/node ./node/node_modules/npm/bin/npm-cli.js test -- src/pages/settings/TracingTab.test.tsx src/components/sessions/SessionTraceExplorer.test.tsx src/lib/traceFormat.test.ts src/pages/settings/settingsCatalogSearch.test.ts`
- `./node/node ./node/node_modules/npm/bin/npm-cli.js run lint`
- `./node/node ./node/node_modules/npm/bin/npm-cli.js run build`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/api/settings.ts \
  dashboard/src/pages/settings/settingsCatalog.ts \
  dashboard/src/pages/SettingsPage.tsx \
  dashboard/src/pages/settings/TracingTab.tsx \
  dashboard/src/api/sessions.ts \
  dashboard/src/hooks/useSessions.ts \
  dashboard/src/pages/SessionsPage.tsx \
  dashboard/src/components/sessions/SessionTraceExplorer.tsx \
  dashboard/src/components/sessions/SessionTraceTree.tsx \
  dashboard/src/components/sessions/SessionTraceTimeline.tsx \
  dashboard/src/components/sessions/SessionTraceSnapshotViewer.tsx \
  dashboard/src/lib/traceFormat.ts \
  dashboard/src/pages/settings/TracingTab.test.tsx \
  dashboard/src/components/sessions/SessionTraceExplorer.test.tsx \
  dashboard/src/lib/traceFormat.test.ts \
  dashboard/src/pages/settings/settingsCatalogSearch.test.ts
git commit -m "feat(tracing): add dashboard tracing settings and explorer"
```

## Task 7: Run integrated verification and clean up rough edges

**Files:**
- Modify: any touched files that still fail lint, style, or edge-case verification
- Test: all targeted backend and dashboard suites above

- [ ] **Step 1: Run focused backend regression suite**

Run:
- `./mvnw -q -Dtest=RuntimeConfigServiceTest,SessionProtoMapperTest,TraceServiceTest,TraceBudgetServiceTest,TraceSnapshotCompressionServiceTest,SettingsControllerTest,SessionsControllerTest,WebSocketChatHandlerTest,WebhookControllerTest,CommandRouterTest,AutoModeSchedulerTest,DelayedSessionActionSchedulerTest,AgentLoopTest,DefaultToolLoopSystemTest,ResponseRoutingSystemTest,Langchain4jAdapterTest test`

Expected: PASS

- [ ] **Step 2: Run strict dashboard checks**

Run:
- `./node/node ./node/node_modules/npm/bin/npm-cli.js run lint`
- `./node/node ./node/node_modules/npm/bin/npm-cli.js run build`

Expected: PASS

- [ ] **Step 3: Run full Maven verification**

Run: `./mvnw clean verify -P strict`

Expected: PASS

- [ ] **Step 4: Manually verify trace UX in the dashboard**

Checklist:
- tracing settings persist and reload correctly
- payload snapshots remain off by default
- session trace list appears after a traced run
- export produces OTel-compatible JSON
- snapshot truncation and eviction markers are visible when budget is exceeded

- [ ] **Step 5: Commit final polish**

```bash
git add .
git commit -m "test(tracing): verify tracing end-to-end"
```

## Plan Review Notes

- This plan intentionally separates trace foundation from runtime instrumentation so protobuf/schema churn does not get mixed with LLM/tool changes.
- `SessionsPage.tsx` should not absorb the entire explorer UI; keep new session-trace UI in extracted top-level components to stay within dashboard file-size limits.
- If existing ingress code reveals an additional canonical entry point for Telegram or plugin-driven inbound messages during implementation, reuse the same trace helper instead of creating a second propagation path.

## Execution Notes

- No subagent harness is available in this Codex environment, so execution should use `superpowers:executing-plans` in the current session after plan approval.
- Preserve backward compatibility for sessions without traces and for runtime configs saved before `TracingConfig` existed.
- Keep payload snapshots strictly gated by config. Do not capture or compress payloads on hot paths when `payloadSnapshotsEnabled=false`.
