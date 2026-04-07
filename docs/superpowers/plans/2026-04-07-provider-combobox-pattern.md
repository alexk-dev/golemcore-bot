# Provider Combobox Pattern Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a reusable Tailwind-style autocomplete combobox component and switch the LLM provider name field to use it.

**Architecture:** Add a generic combobox shell in `dashboard/src/components/common` that owns keyboard navigation, freeform input behavior, and the Tailwind-style dropdown presentation. Keep the provider settings screen as the first consumer so future autocomplete fields can migrate without duplicating UI logic.

**Tech Stack:** React 18, TypeScript, Tailwind utility classes, Vitest

---

### Task 1: Define the reusable combobox contract

**Files:**
- Create: `dashboard/src/components/common/AutocompleteCombobox.test.tsx`
- Modify: `dashboard/src/pages/settings/LlmProvidersTab.test.tsx`

- [ ] **Step 1: Write the failing test**

Add tests that assert the new combobox renders:
- a combobox input shell
- a trailing chevron trigger button
- freeform input support
- Tailwind-style wrapper classes that match the intended pattern

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- src/components/common/AutocompleteCombobox.test.tsx src/pages/settings/LlmProvidersTab.test.tsx`
Expected: FAIL because the generic combobox does not exist yet and the provider tab still uses the old specialized component.

### Task 2: Implement the generic combobox and apply it to providers

**Files:**
- Create: `dashboard/src/components/common/AutocompleteCombobox.tsx`
- Modify: `dashboard/src/components/common/ProviderNameCombobox.tsx`
- Modify: `dashboard/src/pages/settings/LlmProvidersTab.tsx`
- Modify: `dashboard/src/styles/custom.scss`

- [ ] **Step 3: Write minimal implementation**

Implement a reusable combobox component with:
- freeform input support
- keyboard navigation and outside-click dismissal
- embedded chevron button
- Tailwind-style dropdown panel and option states

Apply it to the provider add field via a thin wrapper or direct use.

- [ ] **Step 4: Run tests to verify it passes**

Run: `npm test -- src/components/common/AutocompleteCombobox.test.tsx src/pages/settings/LlmProvidersTab.test.tsx`
Expected: PASS

### Task 3: Verify no regressions in the touched settings surface

**Files:**
- Test: `dashboard/src/components/common/ProviderNameCombobox.test.tsx`
- Test: `dashboard/src/pages/settings/LlmProvidersTab.test.tsx`

- [ ] **Step 5: Run focused verification**

Run: `npm test -- src/components/common/AutocompleteCombobox.test.tsx src/components/common/ProviderNameCombobox.test.tsx src/pages/settings/LlmProvidersTab.test.tsx`
Expected: PASS

- [ ] **Step 6: Run production build**

Run: `npm run build`
Expected: PASS
