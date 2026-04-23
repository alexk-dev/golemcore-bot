# Context Window E2E

`.github/workflows/context-window-e2e.yml` runs an isolated AgentLoop functional test against an OpenAI-compatible API. The test starts the Spring context, configures a temporary model catalog/runtime config, writes oversized workspace instructions, sends one webhook-channel message directly into `AgentLoop`, and verifies:

- the LLM call succeeds without provider context overflow;
- the response includes the expected verification marker;
- the captured `LlmRequest.systemPrompt` stays inside the computed system-prompt budget;
- low-priority workspace instruction tail content is dropped from the prompt;
- tool schemas are absent when runtime tools are disabled.

Required GitHub Actions secrets:

| Secret | Required | Description |
|--------|----------|-------------|
| `AGENTLOOP_E2E_OPENAI_BASE_URL` | Yes | OpenAI-compatible base URL, for example `https://api.openai.com/v1` or a compatible gateway |
| `AGENTLOOP_E2E_OPENAI_API_KEY` | Yes | API key for the compatible endpoint |
| `AGENTLOOP_E2E_MODEL` | Yes | Upstream model id sent to the provider, for example `gpt-4o-mini` or `openai/gpt-4o-mini` |

Optional secrets:

| Secret | Default | Description |
|--------|---------|-------------|
| `AGENTLOOP_E2E_PROVIDER` | `openai` | Runtime provider id used in the model catalog |
| `AGENTLOOP_E2E_REASONING` | empty | Reasoning effort to bind to the balanced tier |
| `AGENTLOOP_E2E_LEGACY_API` | `true` | `true` uses `/v1/chat/completions`; `false` uses the Responses API path |
| `AGENTLOOP_E2E_SUPPORTS_TEMPERATURE` | `true` | Set to `false` for models that reject temperature |
| `AGENTLOOP_E2E_MAX_INPUT_TOKENS` | `16000` | Model max-input-token value used for budgeting assertions |
| `AGENTLOOP_E2E_PAYLOAD_KB` | `16` | Large user payload size for the AgentLoop turn |
| `AGENTLOOP_E2E_TIMEOUT_SECONDS` | `180` | End-to-end response wait timeout |
| `AGENTLOOP_E2E_REQUEST_TIMEOUT_SECONDS` | `180` | Provider HTTP request timeout |
| `AGENTLOOP_E2E_MAX_SNAPSHOT_KB` | `1024` | Trace snapshot cap for captured LLM request/response payloads |

Local run:

```bash
AGENTLOOP_E2E_ENABLED=true \
AGENTLOOP_E2E_OPENAI_BASE_URL=https://api.example.com/v1 \
AGENTLOOP_E2E_OPENAI_API_KEY=... \
AGENTLOOP_E2E_MODEL=... \
./mvnw -Dtest=AgentLoopContextWindowExternalE2ETest -Dfrontend.skip=true test
```
