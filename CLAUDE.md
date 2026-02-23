# CLAUDE.md — GolemCore Bot

## Build & Test

```bash
./mvnw clean package -DskipTests   # build
./mvnw test                         # run tests
./mvnw clean verify -P strict       # full check (tests + PMD + SpotBugs)
```

To run the app locally, configure provider API keys in `preferences/runtime-config.json` (recommended: via the dashboard at `/dashboard`).

Common env vars (Spring) for local/Docker:

- `STORAGE_PATH` (workspace base path)
- `TOOLS_WORKSPACE` (sandbox for filesystem/shell tools)

---

## Architecture

Spring Boot 4.0.2, Java 17, Hexagonal Architecture (Ports & Adapters).

### Package Structure

```
me.golemcore.bot
├── adapter/inbound/          Telegram (TelegramAdapter, TelegramVoiceHandler), CommandRouter, Webhook (WebhookController)
├── adapter/outbound/         LLM, storage, browser, MCP, voice (ElevenLabsAdapter)
├── domain/component/         Interfaces: ToolComponent, SkillComponent, etc.
├── domain/loop/              AgentLoop, AgentContextHolder
├── domain/model/             AgentContext, AgentSession, Message, Skill, ToolDefinition, ContextAttributes, etc.
├── domain/service/           SessionService, SkillService, CompactionService, VoiceResponseHandler, etc.
├── domain/system/            Ordered pipeline systems (see below)
├── port/inbound/             ChannelPort, CommandPort
├── port/outbound/            LlmPort, StoragePort, BrowserPort, VoicePort, McpPort, SessionPort, RagPort, ConfirmationPort
├── security/                 InjectionGuard, InputSanitizer, AllowlistValidator
└── tools/                    FileSystemTool, ShellTool, BrowserTool, TierTool, VoiceResponseTool, etc.
```

### Agent Loop Pipeline

| Order | System                    | Purpose |
|-------|--------------------------|---------|
| 10    | `InputSanitizationSystem` | Sanitization, injection detection |
| 18    | `AutoCompactionSystem`    | Auto-compact when context nears limit |
| 20    | `ContextBuildingSystem`   | System prompt, memory, skills, tools, MCP, tier resolution |
| 25    | `DynamicTierSystem`       | Upgrade model tier mid-conversation if needed |
| 30    | `ToolLoopExecutionSystem` | LLM calls, tool execution loop, plan intercept |
| 50    | `MemoryPersistSystem`     | Persist memory |
| 55    | `SkillPipelineSystem`     | Auto-transition between skills |
| 55    | `RagIndexingSystem`       | Index conversations for RAG |
| 57    | `TurnOutcomeFinalizationSystem` | Build canonical TurnOutcome from domain state |
| 58    | `PlanFinalizationSystem`  | Plan mode: detect plan completion, publish approval event |
| 58    | `OutgoingResponsePreparationSystem` | Prepare OutgoingResponse from LLM results |
| 59    | `FeedbackGuaranteeSystem` | Fallback OutgoingResponse if none produced upstream |
| 60    | `ResponseRoutingSystem`   | Send response to channel |

Max iterations: `bot.agent.max-iterations=20`.

### Port/Adapter Boundaries

Domain code (`domain/`) depends only on port interfaces (`port/`). Never import adapter classes in domain code.

```
domain/ -> port/         OK
adapter/ -> port/        OK
adapter/ -> domain/      OK (models and services only)
domain/ -> adapter/      PROHIBITED
```

---

## Coding Rules

### Pipeline Flags & Cross-System Contracts

- **Rule:** Any value used as a **contract between pipeline systems/tools** (i.e. read/written by more than one component) **must** be represented as a canonical key in `ContextAttributes`.
- **No ad-hoc string keys** inside individual `*System`/`*Tool` classes.
- **Typed fields / accessors in `AgentContext` are allowed** for ergonomics, but they must remain consistent with the canonical `ContextAttributes` contract when that contract is used downstream.
- If a legacy key is removed, **update tests** to match the new contract. Tests should assert **observable behavior / contracts**, not internal implementation details.

### Java Style

- **No `var`** — always declare types explicitly
- **No wildcard imports** — use explicit imports (`import java.util.List`, not `import java.util.*`)
- **No `@Autowired`** — constructor injection only via `@RequiredArgsConstructor` + `private final` fields
- **No `@Lazy`** — masks circular dependency problems; break cycles architecturally (see below)
- **No `@ConditionalOnProperty`** — all beans always exist, use `isEnabled()` for runtime checks
- Static imports allowed in tests only

### Class Organization

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ExampleService {
    // 1. Static constants
    private static final String DIR_NAME = "examples";
    // 2. Injected dependencies (private final)
    private final StoragePort storagePort;
    // 3. Mutable state (caches, registries)
    private final Map<String, Item> cache = new ConcurrentHashMap<>();
    // 4. @PostConstruct
    // 5. Public interface methods (@Override)
    // 6. Public methods
    // 7. Private methods
}
```

### Naming

| Suffix | Layer | Example |
|--------|-------|---------|
| `*Service` | Domain services | `SessionService` |
| `*System` | Pipeline systems | `ToolLoopExecutionSystem` |
| `*Tool` | Tool implementations | `FileSystemTool` |
| `*Adapter` | Outbound adapters | `Langchain4jAdapter` |
| `*Port` | Port interfaces | `LlmPort`, `StoragePort` |
| `*Component` | Component interfaces | `ToolComponent` |

Methods: `get*` (throw if missing), `find*` (return Optional), `is*`/`has*` (boolean), `create*`/`build*` (factory), `process` (pipeline), `execute` (action).

### Lombok

- `@RequiredArgsConstructor` on services/adapters/systems/tools for injection
- `@Slf4j` on any class that logs
- `@Data` on model POJOs, `@Builder` on request/response objects
- `@NoArgsConstructor` + `@AllArgsConstructor` on Jackson-deserialized models
- **Gotcha:** computed getters in `@Data` classes get serialized by Jackson — mark them `@JsonIgnore`

### Logging

Use `@Slf4j`. Parametrized messages only, no string concatenation.

```java
log.info("[MCP] Starting server for skill: {}", skillName);    // OK
log.info("Starting server for " + skillName);                   // PROHIBITED
```

Levels: `error` (failures + exception), `warn` (recoverable), `info` (milestones), `debug` (internal flow), `trace` (raw content).

### Error Handling

- No custom exception hierarchy — use standard exceptions (`IllegalStateException`, `IllegalArgumentException`)
- Use `Optional` for lookups, never return `null` from public methods
- Catch broadly in I/O layers with `// NOSONAR` comment for intentional broad catches

### Spring Stereotypes

- `@Service` — domain services
- `@Component` — adapters, tools, infrastructure
- `@Configuration` — config classes; `@Bean` methods with injected fields **must be `static`**

### Circular Dependencies

**No `@Lazy`** — it masks architectural problems. Break cycles by:
1. Extracting a shared interface/service that both sides depend on
2. Using Spring's `ApplicationEventPublisher` for one-way notifications
3. Moving the dependency into a method parameter instead of a constructor field

---

## Git Workflow

- **Direct commits/pushes to `main` are prohibited.**
- Always create a feature branch from `main`, commit there, and open a Pull Request.
- Merge to `main` only through PR after required checks pass.
- PR description must include a detailed `Summary` section.
- Do not add a `Verification` section in PR description unless explicitly requested.

---

## Commit Messages

Follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/).

### Format

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

### Types

| Type | When to use |
|------|-------------|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `test` | Adding or updating tests |
| `docs` | Documentation only |
| `chore` | Build config, CI, dependencies, tooling |
| `perf` | Performance improvement |
| `style` | Formatting, whitespace (no logic change) |

### Scope

Use module/area name: `llm`, `telegram`, `tools`, `skills`, `mcp`, `auto`, `security`, `storage`, `loop`, `voice`, `webhook`.

### Rules

- Imperative mood: "add feature", not "added feature"
- Subject line under 72 characters, no period at end
- Breaking changes: append `!` after type/scope AND add `BREAKING CHANGE:` footer

### Examples

```
feat(tools): add BrowserTool screenshot mode
fix(llm): handle empty response from Anthropic API
refactor(llm): extract tier resolution from ContextBuildingSystem
test(mcp): add McpClient lifecycle tests
chore: upgrade langchain4j to 1.11.0
feat(skills)!: rename nextSkill field to next_skill in YAML frontmatter

BREAKING CHANGE: skill YAML files must use next_skill instead of nextSkill.
```

---

## Testing

Tests mirror main source structure.

- Test class: `*Test` suffix
- Test method: `shouldDoSomethingWhenCondition()` — no `test` prefix
- Pattern: Arrange-Act-Assert
- Mocking: Mockito, create mocks in `@BeforeEach`
- Unit tests must not use real network/socket connections; for OkHttp-based adapters use in-memory test doubles (e.g. `src/test/java/me/golemcore/bot/testsupport/http/OkHttpMockEngine.java`)
- For varargs mocks, use custom `Answer` on mock creation (not `when().thenAnswer()`)
- Use `@ParameterizedTest` + `@ValueSource` for input validation

```java
@Test
void shouldRejectPathTraversalAttempt() {
    // Arrange
    Map<String, Object> params = Map.of("path", "../../../etc/passwd");
    // Act
    ToolResult result = tool.execute(params).join();
    // Assert
    assertTrue(result.getError().contains("traversal"));
}
```

---

## Key Patterns

### AgentContext

Uses `@Builder` only (no no-arg constructor):
```java
AgentContext context = AgentContext.builder()
        .session(session).messages(messages)
        .channel(channelPort).chatId("123")
        .build();
```

### AgentSession

Field is `chatId` (not `channelId`).

### ToolDefinition

Field is `inputSchema` (not `parameters`).

### ToolResult

`ToolResult.success(output)` stores in `output` field. `ToolResult.failure(error)` stores in `error` field.

### Tools

All tools implement `ToolComponent`. Tools using `AgentContextHolder` (ThreadLocal) must NOT use `CompletableFuture.supplyAsync()` — ThreadLocal not propagated to ForkJoinPool.

### Adding a New Tool

1. Create class in `tools/` implementing `ToolComponent`
2. Implement `getDefinition()` (JSON Schema), `execute()`, `isEnabled()`
3. Add config flag `bot.tools.<name>.enabled` in `application.properties`

### Adding a New System

1. Implement `AgentSystem` in `domain/system/`
2. Set `@Order(N)` to control pipeline position
3. Implement `process(AgentContext)`, `shouldProcess()`, `isEnabled()`

### Storage

Local filesystem only. `StoragePort` uses "directory" and "path" terminology.
Base path: `${user.home}/.golemcore/workspace`. Directories: `sessions/`, `memory/`, `skills/`, `usage/`, `preferences/`.

### MCP Client

Lightweight MCP over stdio (Jackson + ProcessBuilder, JSON-RPC 2.0). `McpPort` outbound port interface. `McpClientManager` manages pool of `McpClient` by skill name with idle timeout. `McpToolAdapter` (NOT a Spring bean) wraps MCP tools as `ToolComponent`.

Skill frontmatter:
```yaml
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  startup_timeout: 30
  idle_timeout: 10
```

### Voice

ElevenLabs for STT + TTS. Voice prefix mechanism: LLM starts response with `🔊` -> `ResponseRoutingSystem` detects, strips prefix, synthesizes TTS, sends voice. Falls back to text on failure.

**API Documentation:**
- [ElevenLabs API Reference](https://elevenlabs.io/docs/api-reference/overview)
- [Error Messages & Status Codes](https://elevenlabs.io/docs/developers/resources/error-messages)
- STT endpoint: `POST /v1/speech-to-text` (model: `scribe_v1`)
- TTS endpoint: `POST /v1/text-to-speech/{voice_id}`

**Error Handling:** Handles 401 (auth), 429 (rate limit), 500/503 (server errors), network errors. See `ElevenLabsAdapter` for implementation.

### Webhooks

Inbound HTTP webhooks (OpenClaw-style, WebFlux). Three endpoint types: `/api/hooks/wake` (fire-and-forget, 200), `/api/hooks/agent` (full agent turn, 202), `/api/hooks/{name}` (custom mapped). Configuration stored in `UserPreferences.WebhookConfig` (not application.properties).

**Components:**
- `WebhookController` — REST endpoints (`Mono<ResponseEntity<WebhookResponse>>`)
- `WebhookAuthenticator` — Bearer token + HMAC-SHA256 with constant-time comparison
- `WebhookChannelAdapter` — `ChannelPort` implementation, captures responses, sends callbacks
- `WebhookCallbackSender` — Reactive `WebClient` POST with exponential backoff retry
- `WebhookPayloadTransformer` — `{field.path}` placeholder resolution from JSON

**Message flow:** `WebhookController` → `ApplicationEventPublisher` → `InboundMessageEvent` → `SessionRunCoordinator` → `AgentLoop` pipeline. External payloads wrapped with `[EXTERNAL WEBHOOK DATA]` safety markers.

See [docs/WEBHOOKS.md](docs/WEBHOOKS.md) for the full guide.
