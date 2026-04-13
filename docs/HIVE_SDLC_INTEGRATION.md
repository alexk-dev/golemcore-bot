# Hive SDLC Integration

`golemcore-bot` includes built-in Hive SDLC tools for Hive card-bound turns. These tools are runtime functionality, not external plugins.

## Availability

Hive SDLC tools are advertised only when both conditions are true:

1. The current session channel type is `hive`.
2. The specific SDLC function is enabled in Hive settings.

All SDLC function toggles default to enabled when Hive integration is active. Disabling Hive disables the effective availability of every Hive SDLC function.

## Runtime settings

The toggles are stored under `preferences/hive.json`:

```json
{
  "enabled": true,
  "serverUrl": "https://hive.example.com",
  "displayName": "Builder",
  "hostLabel": "builder-lab-a",
  "autoConnect": true,
  "managedByProperties": false,
  "sdlc": {
    "currentContextEnabled": true,
    "cardReadEnabled": true,
    "cardSearchEnabled": true,
    "threadMessageEnabled": true,
    "reviewRequestEnabled": true,
    "followupCardCreateEnabled": true,
    "lifecycleSignalEnabled": true
  }
}
```

The dashboard exposes these toggles on **Settings → Hive → SDLC agent functions**.

## Tools

| Tool | Purpose |
| --- | --- |
| `hive_get_current_context` | Returns current `threadId`, `cardId`, `commandId`, `runId`, `golemId`, channel, and chat id. |
| `hive_get_card` | Reads the active Hive card or an explicit `card_id`. |
| `hive_search_cards` | Searches cards by service, board, kind, parent/epic/review/objective filters, and archive flag. |
| `hive_post_thread_message` | Posts an operator-facing SDLC note into the active or explicit Hive thread. |
| `hive_request_review` | Requests Hive review for the active or explicit card. Explicit reviewer golems/team can be supplied; otherwise the tool reuses reviewer settings already stored on the card. |
| `hive_create_followup_card` | Creates a follow-up/subtask/review card. Can inherit the active card as parent. |
| `hive_lifecycle_signal` | Emits structured board lifecycle signals through Hive event ingestion. |

Supported lifecycle signals are:

- `WORK_STARTED`
- `PROGRESS_REPORTED`
- `BLOCKER_RAISED`
- `BLOCKER_CLEARED`
- `REVIEW_REQUESTED`
- `WORK_COMPLETED`
- `WORK_FAILED`
- `WORK_CANCELLED`
- `REVIEW_STARTED`
- `REVIEW_APPROVED`
- `CHANGES_REQUESTED`

## Hive API usage

The bot uses its existing Hive machine session and bearer access token. The SDLC tools call the machine-scoped Hive endpoints added for this integration:

- `GET /api/v1/golems/{golemId}/sdlc/cards/{cardId}`
- `GET /api/v1/golems/{golemId}/sdlc/cards?...`
- `POST /api/v1/golems/{golemId}/sdlc/cards`
- `POST /api/v1/golems/{golemId}/sdlc/threads/{threadId}/messages`
- `POST /api/v1/golems/{golemId}/sdlc/cards/{cardId}:request-review`
- `POST /api/v1/golems/{golemId}/events:batch` for lifecycle signals

These endpoints require Hive machine scopes:

- `golems:sdlc:read`
- `golems:sdlc:write`

Existing machine sessions get these scopes during token rotation; new sessions get them during enrollment.

## Authority model

Hive remains the owner of board state. The bot reads Hive SDLC state, creates approved SDLC records through Hive APIs, and emits structured lifecycle signals. It does not mutate card columns directly.

Use this direction for collaboration:

```text
Golem A -> Hive card/thread/signal -> Hive policy/assignment -> Golem B
```

Avoid direct golem-to-golem chat as the primary workflow because it bypasses card history, audit, budget, role policy, and approval gates.

## Multi-golem SDLC topology

A practical team layout is:

| Role | Responsibility |
| --- | --- |
| Planner / PM golem | Breaks objectives into epics/tasks, writes acceptance criteria, creates follow-up cards. |
| Implementer golem(s) | Executes task cards, reports progress/blockers/completion, creates implementation follow-ups. |
| Reviewer golem(s) | Reviews implementation/review cards, emits `REVIEW_STARTED`, `REVIEW_APPROVED`, or `CHANGES_REQUESTED`. |
| QA golem | Runs integration/regression checks, reports evidence, creates bug/follow-up cards. |
| Release golem | Checks final gates, prepares release notes, coordinates deployment/rollback cards. |
| Human operator | Owns priorities, risk acceptance, manual overrides, credentials, and exceptional approvals. |

Recommended Hive setup:

1. Register each bot instance as a separate golem.
2. Bind golems to role/team groupings such as `backend`, `qa`, `reviewers`, and `release`.
3. Enable separation-of-duties policy: the implementation assignee should not be an eligible reviewer for the same card.
4. Use Hive policy groups for model/tool budgets and per-role runtime capabilities.
5. Keep board transitions driven by lifecycle signals and board signal mappings, not transcript parsing.

## Card lifecycle

A typical engineering lifecycle is:

1. **Intake / Objective**
   - A human or planner golem creates an objective/epic.
   - Planner decomposes it into task cards with `parentCardId`, `epicCardId`, `dependsOnCardIds`, and `objectiveId` links.
2. **Assignment**
   - Hive assigns a task card to an implementer golem and records reviewer golems/team plus required review count.
3. **Dispatch**
   - Hive sends a command on the card thread. The bot receives `cardId`, `threadId`, `commandId`, `runId`, and `golemId`.
4. **Work start**
   - Implementer emits `WORK_STARTED`; a typical engineering board auto-applies `ready -> in_progress`.
5. **Progress / blocker handling**
   - Implementer posts notes and emits `PROGRESS_REPORTED` for audit-only updates.
   - `BLOCKER_RAISED` can auto-move to `blocked`; `BLOCKER_CLEARED` can suggest or apply a return to active work.
6. **Completion / review request**
   - Implementer emits `WORK_COMPLETED` or `REVIEW_REQUESTED`.
   - Hive moves to `review` according to board policy and can activate/create review work.
7. **Review**
   - Reviewer emits `REVIEW_STARTED` when taking the review.
   - Reviewer emits `REVIEW_APPROVED` when accepted or `CHANGES_REQUESTED` when changes are needed.
8. **Change loop**
   - On `CHANGES_REQUESTED`, Hive records the decision and routes work back through the implementation path or creates follow-up cards.
9. **QA gate**
   - QA golem runs required verification and emits lifecycle signals with evidence references.
10. **Release**
    - Release golem validates GitHub/CI gates, writes release notes, and closes release cards when complete.

## Recommended engineering board mappings

| Signal | Decision | Target column |
| --- | --- | --- |
| `WORK_STARTED` | `AUTO_APPLY` | `in_progress` |
| `PROGRESS_REPORTED` | `IGNORE` | — |
| `BLOCKER_RAISED` | `AUTO_APPLY` | `blocked` |
| `BLOCKER_CLEARED` | `SUGGEST_ONLY` | `in_progress` |
| `REVIEW_REQUESTED` | `AUTO_APPLY` | `review` |
| `WORK_COMPLETED` | `AUTO_APPLY` | `review` |
| `REVIEW_STARTED` | `AUTO_APPLY` | `review` |
| `REVIEW_APPROVED` | `AUTO_APPLY` | `done` |
| `CHANGES_REQUESTED` | `AUTO_APPLY` or `SUGGEST_ONLY` | implementation path (`in_progress`) or review side-effect policy |
| `WORK_FAILED` | `SUGGEST_ONLY` | `blocked` or failure triage column |
| `WORK_CANCELLED` | `SUGGEST_ONLY` | current/cancelled-like column |

Exact mappings remain board-specific. Hive validates the target column and transition before applying a move.

## Manual API calls

Operators normally should not call the SDLC API manually. The bot tools call the machine API for the active golem session. Manual API calls are useful only for diagnostics, migration scripts, or integration tests.
