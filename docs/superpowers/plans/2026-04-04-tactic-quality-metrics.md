# Tactic Quality Metrics Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace fake tactic quality/runtime metric defaults with derived values from observed runs, and show `n/a` in the UI until a metric is actually available.

**Architecture:** Add a dedicated domain service that derives tactic quality/runtime metrics from `artifact-bundles`, `runs`, and `run-verdicts`, then apply it centrally when tactic records are read so projections, BM25 rebuilds, and vector search all see the same values. Keep search/scoring metrics numeric and unchanged; only quality/runtime metrics become nullable until computed.

**Tech Stack:** Java 17, Spring services, JUnit 5, React 18, TypeScript, Vitest

---

### Task 1: Add backend tests for derived tactic metrics

**Files:**
- Modify: `src/test/java/me/golemcore/bot/domain/service/TacticRecordServiceTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/service/PromotionWorkflowServiceTest.java`
- Modify: `src/test/java/me/golemcore/bot/domain/service/EvolutionCandidateServiceTacticRecordTest.java`

- [ ] **Step 1: Write failing tests for tactic records with no observed runtime data**
- [ ] **Step 2: Run the focused backend tests and confirm they fail for the current fake defaults**
- [ ] **Step 3: Extend tests to cover observed success rate, local usage success, recency score, and null benchmark win rate**
- [ ] **Step 4: Re-run the focused backend tests and confirm the failures are about missing derived behavior**

### Task 2: Implement derived tactic quality/runtime metrics

**Files:**
- Create: `src/main/java/me/golemcore/bot/domain/service/TacticQualityMetricsService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/TacticRecordService.java`
- Modify: `src/main/java/me/golemcore/bot/domain/service/EvolutionCandidateService.java`

- [ ] **Step 1: Add a dedicated service that derives metrics from bundle bindings, runs, verdicts, and timestamps**
- [ ] **Step 2: Remove fake `0.0` and `1.0` defaults for quality/runtime metrics from tactic creation and normalization**
- [ ] **Step 3: Apply derived metrics when tactic records are read so projections and index rebuilds see the same values**
- [ ] **Step 4: Run the focused backend tests and confirm they pass**

### Task 3: Add frontend tests for nullable metric rendering

**Files:**
- Modify: `dashboard/src/components/selfevolving/SelfEvolvingTacticWhyPanel.test.tsx`
- Modify: `dashboard/src/components/selfevolving/SelfEvolvingTacticSearchWorkspace.test.tsx`

- [ ] **Step 1: Write failing tests asserting `n/a` for null quality/runtime metrics**
- [ ] **Step 2: Run the focused Vitest suite and confirm the UI tests fail before the implementation**

### Task 4: Update frontend metric presentation

**Files:**
- Modify: `dashboard/src/components/selfevolving/SelfEvolvingTacticWhyPanel.tsx`
- Modify: `dashboard/src/components/selfevolving/SelfEvolvingTacticResultsList.tsx`
- Modify: `dashboard/src/components/selfevolving/selfEvolvingUi.ts`

- [ ] **Step 1: Keep search/scoring metric formatting numeric**
- [ ] **Step 2: Ensure quality/runtime metrics render `n/a` when the backend returns `null`**
- [ ] **Step 3: Re-run the focused Vitest suite and confirm it passes**

### Task 5: Verify the end-to-end contract

**Files:**
- Modify: `src/test/java/me/golemcore/bot/domain/service/SelfEvolvingProjectionServiceTest.java`

- [ ] **Step 1: Add or update projection coverage so tactic DTOs preserve nullable quality/runtime metrics**
- [ ] **Step 2: Run focused backend and frontend verification commands**
- [ ] **Step 3: If all focused checks pass, run one broader regression slice covering self-evolving tactic behavior**
