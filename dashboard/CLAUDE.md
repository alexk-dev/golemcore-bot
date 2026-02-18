# CLAUDE.md — GolemCore Dashboard

## Build & Run

```bash
cd dashboard
npm install                  # install deps
npm run dev                  # dev server (Vite, proxies /api → localhost:8080)
npm run build                # tsc + vite build → ../src/main/resources/static/dashboard
npm run lint                 # ESLint strict check
npm run lint:fix             # ESLint autofix
```

**From project root (Maven):**

```bash
./mvnw clean package -DskipTests   # builds dashboard via frontend-maven-plugin
```

---

## Tech Stack

React 18, TypeScript 5.7, Vite 6, React Router 6, TanStack React Query 5, Zustand 5, React Bootstrap + Bootswatch Zephyr, SCSS, Axios.

---

## Architecture

### Package Structure

```
src/
├── api/            HTTP client and API functions (one file per resource)
├── components/     Reusable UI components, grouped by feature
│   ├── auth/
│   ├── chat/
│   ├── common/
│   └── layout/
├── hooks/          React Query hooks and custom hooks (one file per resource)
├── pages/          Page-level components (routed by App.tsx)
├── store/          Zustand stores (auth, theme, contextPanel)
└── styles/         SCSS (global styles, Bootstrap overrides)
```

### Data Flow

```
Page → useHook (React Query) → api function (Axios) → /api/*
                                                        ↓
Page ← useHook (cache update) ← api function ← response
```

- **API layer** (`api/`): Pure async functions, no hooks, no React imports.
- **Hooks layer** (`hooks/`): React Query wrappers, cache invalidation, no UI logic.
- **Pages** (`pages/`): Compose hooks and components, handle user interactions.
- **Components** (`components/`): Receive data via props, emit events via callbacks.

---

## Strict Code Quality Rules

### 1. No God Objects

- **Max component length: 200 lines of JSX.** If a page exceeds this, extract sub-components.
- **Max file length: 400 lines.** Split files that grow beyond this threshold.
- **Max function/hook body: 50 lines.** Extract helpers or break into smaller hooks.
- A single component must not manage more than **3 independent pieces of state**. Beyond that, extract state into a custom hook or split the component.

### 2. Law of Demeter (No Deep Chaining)

- **Max property access depth: 2 levels** from received props or hook results. `config.telegram.token` — OK. `config.telegram.auth.inviteCodes[0].value` — extract into a variable or derived value.
- **No optional chaining chains** like `a?.b?.c?.d`. If the shape is that uncertain, define a proper type guard or default.
- Components must not reach into the internal structure of objects they don't own. If a component needs `session.messages[0].role`, pass `firstMessageRole` as a prop instead.

### 3. Single Responsibility

- **API files**: Only HTTP calls and request/response type definitions. No business logic, no transforms beyond serialization.
- **Hooks**: Only React Query config + cache invalidation. No JSX, no direct DOM manipulation, no side effects beyond query/mutation lifecycle.
- **Pages**: Orchestration only — compose hooks, wire callbacks, layout components. No raw `fetch`/`axios` calls.
- **Components**: Pure rendering + local UI state (modals, toggles). No API calls, no store access except via props or dedicated hooks.
- **Stores**: Only cross-cutting client state (auth, theme). No server-state (use React Query).

### 4. Explicit Types

- **No `any`** in new code. Use `unknown` + type narrowing or define proper interfaces.
- **No type assertions** (`as T`) unless narrowing from `unknown` after a runtime check.
- **All function parameters and return types must be explicitly typed.** Relying on inference for public API boundaries is not acceptable.
- **All component props must have a named interface/type.** No inline `{ foo: string, bar: number }` in the function signature when the prop set exceeds 2 fields.

### 5. Naming

| Entity | Convention | Example |
|---|---|---|
| Component | PascalCase, descriptive noun/noun-phrase | `TelegramSettingsCard` |
| Page | `*Page` suffix | `SettingsPage` |
| Hook | `use*` prefix | `useUpdateTelegram` |
| API function | verb + resource (`get*`, `create*`, `update*`, `delete*`) | `updateTelegramConfig` |
| Store | `use*Store` | `useAuthStore` |
| Interface/Type | PascalCase, no `I` prefix | `TelegramConfig` |
| Constants | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| Boolean props/vars | `is*`, `has*`, `should*`, `can*` prefix | `isLoading`, `hasError` |
| Event handlers | `handle*` in component, `on*` in props | `handleSubmit`, `onSave` |

### 6. Component Rules

- **Props interface is always separate**, defined above the component, exported if reused.
- **Destructure props** in the function signature: `function Card({ title, children }: CardProps)`.
- **No nested component definitions.** Every component is a top-level `function` or extracted to its own file. Defining components inside `render`/return or inside other components causes remount on every render.
- **No index as key** in lists unless the list is static and never reordered.
- **Effect discipline**: Every `useEffect` must have a comment explaining *why* it exists (what side effect it synchronizes). If the comment would just restate the code, the effect is probably unnecessary.

### 7. Hook Rules

- One query/mutation per hook function. No hook that internally runs 3 unrelated queries.
- **Query keys are hierarchical arrays**: `['resource']`, `['resource', id]`, `['resource', 'sub']`.
- **Cache invalidation must be explicit** in `onSuccess` — never rely on `refetchInterval` as a substitute for proper invalidation.
- Custom hooks that combine multiple React Query hooks are allowed, but must be in a separate file from the base query hooks.

### 8. Error Handling

- **No empty catch blocks.** At minimum, log with `console.error` or show a toast.
- **No silent swallowing** of Promise rejections. Always `.catch()` or use try/catch.
- API errors propagate to React Query's `error` state — handle them in the consuming component with appropriate UI feedback (toast, inline error, retry button).
- **Discriminated error types** preferred over generic string messages when the UI needs to react differently to different failure modes.

### 9. Immutability & State

- **Never mutate state directly.** Always use spread/destructuring or produce new objects.
- **No `let`** unless reassignment is genuinely required (loop counters, accumulators). Default to `const`.
- **Zustand stores**: Minimal surface area. If a piece of state is only used by one component, use `useState` instead.

### 10. Imports

- **No wildcard imports** (`import * as X`). Use named imports.
- **No circular imports.** API → hooks → pages → components forms a DAG. Never import backwards.
- **Group imports** in order: (1) React/framework, (2) third-party, (3) local absolute paths. Blank line between groups.

---

## Forbidden Patterns

| Pattern | Why | Alternative |
|---|---|---|
| `any` | Defeats type safety | `unknown` + narrowing, or define interface |
| `// @ts-ignore`, `// @ts-expect-error` | Hides real bugs | Fix the type error |
| `useEffect` for derived state | Causes extra renders, stale bugs | `useMemo` or compute inline |
| `useEffect` to sync props→state | Anti-pattern, duplicates source of truth | Use props directly or lift state |
| `setState` inside `useEffect` without deps | Infinite loop risk | Rethink the data flow |
| Inline styles (`style={{}}`) | Unmaintainable, no theming | SCSS classes or Bootstrap utilities |
| String concatenation for classNames | Fragile | Template literals or `clsx` |
| Magic numbers/strings | Unverifiable | Named constants |
| Nested ternaries | Unreadable | Early returns, `if`/`else`, or lookup objects |
| `document.querySelector` in components | Breaks React model | Refs (`useRef`) |
| Default exports for non-page files | Harder to refactor, no auto-import rename | Named exports (pages are the exception) |
| `setTimeout`/`setInterval` without cleanup | Memory leaks | Return cleanup in `useEffect` |

---

## Verifiability Checklist

Before submitting any change, verify:

1. **`npm run lint`** passes with zero warnings.
2. **`npm run build`** succeeds (tsc + vite build).
3. **No `any` introduced** — search the diff for `any` type usage.
4. **No files exceed 400 lines** — check with `wc -l`.
5. **All new interfaces are exported** from the file where they're defined.
6. **React Query keys** follow the hierarchical convention.
7. **No prop drilling beyond 2 levels** — if a prop passes through an intermediary component unchanged, use composition or context.
8. **Every `useEffect` has a justification comment.**
9. **Every component has a descriptive name** that conveys its purpose without reading the implementation.
