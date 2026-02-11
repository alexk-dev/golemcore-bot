# Skills Guide

How to create, configure, and orchestrate skills for the bot.

> **See also:** [Model Routing](MODEL_ROUTING.md) for tier assignment, [Configuration Guide](CONFIGURATION.md) for environment variables, [RAG](RAG.md) for memory integration.

---

## Overview

A **skill** is a markdown file with YAML frontmatter that defines the bot's behavior for a specific task. Skills control the system prompt, required variables, tool integrations (including MCP servers), and pipeline transitions.

```
workspace/skills/
├── greeting/
│   └── SKILL.md
├── code-review/
│   ├── SKILL.md
│   └── vars.json          # per-skill variables
├── research/
│   └── SKILL.md
└── variables.json          # global variables
```

When a user sends a message, the [routing system](#skill-routing) automatically selects the best skill based on the message content. The selected skill's content becomes part of the system prompt sent to the LLM.

---

## Quick Start

### Minimal Skill

Create `workspace/skills/greeting/SKILL.md`:

```markdown
---
name: greeting
description: Handle greetings and casual conversation
---

You are a friendly assistant. When the user greets you,
respond warmly and ask how you can help today.
```

That's it. The bot will automatically discover this skill on the next reload (or call `/skills` to verify).

### Reload Skills

Skills are loaded at startup and can be reloaded at runtime:

- **Slash command:** The LLM can create/delete skills via the `skill_management` tool
- **Programmatic:** `SkillComponent.reload()` re-scans the skills directory

---

## SKILL.md Format

Every skill file follows the same structure:

```markdown
---
# YAML frontmatter (configuration)
name: my-skill
description: What this skill does
---

# Skill content (markdown)

Instructions for the LLM when this skill is active...
```

The frontmatter is separated from the content by `---` delimiters.

---

## Frontmatter Reference

### Core Fields

```yaml
name: my-skill              # Unique identifier (auto-extracted from folder name if omitted)
description: Brief summary   # Shown in /skills list and used for routing
```

If `name` is omitted, it's extracted from the directory path (e.g., `skills/code-review/SKILL.md` becomes `code-review`).

### Requirements

Declare what the skill needs to function. If any requirement is unmet, the skill is marked as **unavailable** and won't be selected by routing.

```yaml
requires:
  env:                       # Required environment variables
    - OPENAI_API_KEY
    - GITHUB_TOKEN
  binary:                    # Required binaries on PATH
    - node
    - npx
  skills:                    # Dependencies on other skills
    - base-assistant
```

### Variables

Variables allow skills to use dynamic values resolved from multiple sources.

```yaml
vars:
  # Full format
  API_KEY:
    description: "API key for the service"
    default: ""
    required: true           # Skill unavailable if not resolved
    secret: true             # Masked in logs as ***

  # Shorthand — string value becomes the default
  ENDPOINT: "https://api.example.com"

  # Minimal — just declares the variable
  TIMEOUT:
```

**Resolution priority** (first match wins):

| Priority | Source | Path |
|----------|--------|------|
| 1 | Per-skill file | `skills/{name}/vars.json` |
| 2 | Global file (skill section) | `variables.json` → `{"my-skill": {"VAR": "value"}}` |
| 3 | Global file (shared section) | `variables.json` → `{"_global": {"VAR": "value"}}` |
| 4 | Environment variable | `System.getenv("VAR")` |
| 5 | Default from frontmatter | `default: "value"` |

If a variable is `required: true` and cannot be resolved from any source, the skill is marked unavailable.

#### Using Variables in Content

Resolved variables are substituted into the skill content as `{{VAR_NAME}}` placeholders:

```markdown
---
name: api-caller
vars:
  ENDPOINT:
    description: "Target API"
    default: "https://api.example.com"
  API_KEY:
    required: true
    secret: true
---

Call the API at {{ENDPOINT}} using key {{API_KEY}}.
```

Unresolved (non-required) placeholders are left as-is in the output.

#### Variable Files

**Per-skill** (`skills/my-skill/vars.json`):
```json
{
  "API_KEY": "sk-abc123",
  "ENDPOINT": "https://custom.api.com"
}
```

**Global** (`variables.json`):
```json
{
  "my-skill": {
    "API_KEY": "sk-abc123"
  },
  "another-skill": {
    "TOKEN": "tok-xyz"
  },
  "_global": {
    "SHARED_VAR": "available-to-all-skills"
  }
}
```

---

## MCP Server Integration

Skills can declare an [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) server. When the skill is activated, the bot starts the MCP server process, discovers its tools, and makes them available to the LLM alongside native tools.

```yaml
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  startup_timeout: 30        # seconds (default: 30)
  idle_timeout: 10            # minutes (default: 5)
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `command` | string | — | **Required.** Command to start the MCP server (stdio transport) |
| `env` | map | `{}` | Environment variables passed to the process |
| `startup_timeout` | int | 30 | Seconds to wait for the server to initialize |
| `idle_timeout` | int | 5 | Minutes of inactivity before the server is stopped |

### Environment Variable Placeholders

Use `${VAR_NAME}` in the `env` section. Placeholders are resolved from:

1. The skill's own resolved variables (from `vars:` section)
2. System environment (`System.getenv()`)

```yaml
vars:
  GITHUB_TOKEN:
    required: true
    secret: true

mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}   # resolved from skill vars
    HOME: ${HOME}                                     # resolved from system env
```

### MCP Lifecycle

1. User message arrives, routing selects this skill
2. `ContextBuildingSystem` detects `skill.hasMcp() == true`
3. `McpClientManager.getOrStartClient(skill)` starts the MCP server process
4. MCP handshake: `initialize` → `tools/list`
5. Each MCP tool is wrapped as `McpToolAdapter` and registered for the LLM
6. LLM can call MCP tools just like native tools
7. After `idle_timeout` minutes without calls, the server process is stopped
8. Tools are unregistered via `ToolExecutionSystem.unregisterTools()`

### Example: GitHub Skill with MCP

```markdown
---
name: github-assistant
description: Work with GitHub repos, issues, and PRs
requires:
  env:
    - GITHUB_TOKEN
  binary:
    - npx
vars:
  GITHUB_TOKEN:
    required: true
    secret: true
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  startup_timeout: 45
  idle_timeout: 10
---

You are a GitHub assistant. Use the available MCP tools to:
- Search repositories
- Read and create issues
- Review pull requests
- Manage branches

Always confirm destructive actions (closing issues, deleting branches) before executing.
```

---

## Skill Pipelines

Pipelines allow skills to **chain together** — when one skill completes, it automatically transitions to the next. This enables multi-step workflows where each step has its own specialized prompt and tools.

### Automatic Transitions (`next_skill`)

The simplest pipeline: after the LLM finishes responding (no more tool calls), automatically activate the next skill.

```yaml
---
name: gather-requirements
description: Collect user requirements for a feature
next_skill: write-spec
---

Ask the user about their requirements. Gather:
1. What problem they're solving
2. Expected inputs and outputs
3. Edge cases and constraints

When you have enough information, summarize the requirements.
```

```yaml
---
name: write-spec
description: Write a technical specification
next_skill: code-generator
---

Based on the conversation history, write a technical specification
covering architecture, API design, and data models.
```

```yaml
---
name: code-generator
description: Generate code from specification
---

Implement the code based on the specification in the conversation.
Follow the project's coding conventions.
```

This creates a 3-step pipeline: **gather-requirements** → **write-spec** → **code-generator**.

**How it works:**

1. `SkillPipelineSystem` (order=55) runs after the LLM responds
2. If the active skill has `next_skill` and there are no pending tool calls, the transition fires
3. The next skill's content replaces the current system prompt
4. The conversation history is preserved — the new skill sees everything from previous steps
5. Maximum pipeline depth: **5** (prevents infinite loops)

### Conditional Transitions (`conditional_next_skills`)

For branching workflows, define multiple possible next skills. The LLM decides which path to take using the `skill_transition` tool.

```yaml
---
name: triage
description: Analyze and classify user requests
conditional_next_skills:
  bug_report: bug-handler
  feature_request: feature-planner
  question: knowledge-base
---

Analyze the user's message and classify it:
- **Bug report** — something is broken or not working as expected
- **Feature request** — the user wants new functionality
- **Question** — the user needs information or help

Once classified, use the `skill_transition` tool to route to the appropriate handler:
- For bugs: transition to `bug-handler`
- For features: transition to `feature-planner`
- For questions: transition to `knowledge-base`
```

The LLM calls the `skill_transition` tool:

```json
{
  "target_skill": "bug-handler",
  "reason": "User described a login page crash — this is a bug report"
}
```

### Pipeline Depth Limit

Pipelines are limited to **5 transitions** to prevent infinite loops. The depth counter is tracked in `AgentContext` and incremented on each transition.

If a pipeline tries to exceed the limit, the transition is blocked and the bot continues with the current skill.

### Combining Automatic and Conditional

A skill can have both `next_skill` and `conditional_next_skills`. The conditional transitions (via `skill_transition` tool) take priority. If the LLM doesn't explicitly transition, the automatic `next_skill` fires as a fallback.

```yaml
---
name: analyzer
description: Analyze input and decide next step
next_skill: default-handler            # fallback if no explicit transition
conditional_next_skills:
  complex: deep-analysis
  simple: quick-response
---

Analyze the user's request. If it requires deep analysis,
use skill_transition to route to `deep-analysis`.
If it's simple, route to `quick-response`.
If unsure, let the automatic transition handle it.
```

---

## Skill Routing

The bot uses a **2-stage hybrid routing** system to select the best skill for each message:

### Stage 0: Fragmented Input Detection

Before routing, `MessageContextAggregator` checks if the user's message is a fragment of a larger thought (e.g., the user sent "and also" as a follow-up). Signals detected:

- Message is very short
- Contains back-references ("this", "that", "it")
- Starts with a continuation word ("and", "also", "but")
- Starts with a lowercase letter
- Previous message was incomplete
- Sent within 60 seconds of the previous message

If 2+ signals are detected, the message is aggregated with recent context for better matching.

### Stage 1: Semantic Search

Generate an embedding of the user's message and compare it against pre-indexed skill embeddings (name + description). Returns top-K candidates ranked by cosine similarity.

- Top-K: 5 (configurable)
- Minimum score: 0.6

### Stage 2: LLM Classifier

A fast LLM picks the best skill from the semantic candidates and assigns a **model tier** (balanced/smart/coding/deep).

The classifier is **skipped** if the semantic score exceeds the threshold (default: 0.95).

### No Match

If no skill matches with sufficient confidence, the bot runs without a skill — using only the base system prompt.

---

## Progressive Loading

To keep the system prompt small, skills use **progressive loading**:

- **No skill selected:** The system prompt includes a brief summary of all available skills (name + description only)
- **Skill selected:** The full skill content is included in the system prompt

This prevents context bloat when many skills are defined.

---

## Creating Skills at Runtime

The LLM can create and manage skills dynamically via the `skill_management` tool (if enabled with `bot.tools.skill-management.enabled=true`).

**Operations:**

| Operation | Description |
|-----------|-------------|
| `create_skill` | Create a new skill with name, description, and content |
| `list_skills` | List all available skills |
| `get_skill` | Get full content of a specific skill |
| `delete_skill` | Delete a skill |

**Validation rules:**
- Name: lowercase alphanumeric + hyphens, must start with letter/digit, max 50 chars (`^[a-z0-9][a-z0-9-]*$`)
- Description: max 200 characters
- Content: max 50,000 characters
- Cannot overwrite existing skills

After creation or deletion, the skill registry is automatically reloaded.

---

## Full Example

A complete skill showcasing all features:

```markdown
---
name: code-review
description: Review code changes and suggest improvements
requires:
  env:
    - GITHUB_TOKEN
  binary:
    - npx
vars:
  GITHUB_TOKEN:
    description: "GitHub personal access token"
    required: true
    secret: true
  REVIEW_STYLE:
    description: "Review style: strict, balanced, or lenient"
    default: "balanced"
  MAX_FILES:
    description: "Maximum files to review in one pass"
    default: "20"
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  idle_timeout: 15
next_skill: review-summary
conditional_next_skills:
  critical_issues: security-audit
  needs_tests: test-generator
---

# Code Review Assistant

Review style: **{{REVIEW_STYLE}}** | Max files: {{MAX_FILES}}

## Instructions

You are a code reviewer. Use the GitHub MCP tools to:

1. Fetch the PR diff
2. Analyze each changed file (up to {{MAX_FILES}} files)
3. Look for: bugs, security issues, performance problems, style violations
4. Post review comments on specific lines

## Review Criteria

- **Security**: SQL injection, XSS, path traversal, hardcoded secrets
- **Performance**: N+1 queries, unnecessary allocations, missing indexes
- **Correctness**: Edge cases, error handling, race conditions
- **Style**: Naming conventions, code organization, documentation

## After Review

- If critical security issues found → transition to `security-audit`
- If code lacks test coverage → transition to `test-generator`
- Otherwise → automatic transition to `review-summary`
```

---

## Configuration Reference

```properties
# Skills
bot.skills.enabled=true
bot.skills.directory=skills
bot.skills.progressive-loading=true

# Skill routing
bot.router.skill-matcher.enabled=false
bot.router.skill-matcher.semantic-search.top-k=5
bot.router.skill-matcher.semantic-search.min-score=0.6
bot.router.skill-matcher.classifier.enabled=true
bot.router.skill-matcher.classifier.model=openai/gpt-5-mini
bot.router.skill-matcher.skip-classifier-threshold=0.95
bot.router.skill-matcher.cache.enabled=true
bot.router.skill-matcher.cache.ttl-minutes=60

# Skill tools
bot.tools.skill-management.enabled=true
bot.tools.skill-transition.enabled=true

# MCP
bot.mcp.enabled=true
bot.mcp.default-startup-timeout=30
bot.mcp.default-idle-timeout=5
```
