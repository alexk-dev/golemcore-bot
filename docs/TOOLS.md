# Tools Guide

Built-in tools that the agent can call (via function/tool calling).

## Tool Safety Model

- Most tools can be enabled/disabled at runtime in `preferences/runtime-config.json` (dashboard: Settings).
- File and shell access are sandboxed to the tool workspace (`bot.tools.*.workspace`).
- Destructive actions may require user confirmation when tool confirmations are enabled.

## Runtime Configuration

Tools are primarily controlled via `runtime-config.json` under `tools` and `security`:

```json
{
  "tools": {
    "filesystemEnabled": true,
    "shellEnabled": true,
    "browserEnabled": true,
    "braveSearchEnabled": false,
    "braveSearchApiKey": "...",
    "skillManagementEnabled": true,
    "skillTransitionEnabled": true,
    "tierEnabled": true,
    "goalManagementEnabled": true
  },
  "security": {
    "toolConfirmationEnabled": false,
    "toolConfirmationTimeoutSeconds": 60
  }
}
```

## Workspace Sandboxing (FileSystem/Shell)

File and shell tools operate only inside the configured workspace:

- `bot.tools.filesystem.workspace`
- `bot.tools.shell.workspace`

In Docker, set these via env and mount volumes:

```bash
docker run -d \
  -e STORAGE_PATH=/app/workspace \
  -e TOOLS_WORKSPACE=/app/sandbox \
  -v golemcore-bot-data:/app/workspace \
  -v golemcore-bot-sandbox:/app/sandbox \
  -p 8080:8080 \
  golemcore-bot:latest
```

## Built-in Tools

### `filesystem`

Sandboxed file operations (read/write/list/create/delete/send) under the tool workspace.

Operations:

- `read_file`, `write_file`, `list_directory`, `create_directory`, `delete`, `file_info`, `send_file`

### `shell`

Sandboxed command execution under the tool workspace.

Input fields:

- `command` (required)
- `timeout` (seconds; default/max enforced)
- `workdir` (relative to tool workspace)

### `browse`

Headless browsing (Playwright) to extract page `text`, `html`, or capture `screenshot`.

Input fields:

- `url` (required)
- `mode`: `text` | `html` | `screenshot`

### `brave_search`

Web search via Brave Search API.

Requires `tools.braveSearchEnabled=true` and `tools.braveSearchApiKey` in runtime config.

### `skill_management`

Create/list/get/delete skills at runtime (writes under `workspace/skills/` in the storage workspace).

### `skill_transition`

Switch active skill/pipeline step.

### `set_tier`

Switch model tier mid-conversation (`balanced`, `smart`, `coding`, `deep`).

### `goal_management`

Auto Mode goal/task/diary management.

### `memory`

Structured memory operations for autonomous workflows.

Operations:

- `memory_add`, `memory_search`, `memory_update`, `memory_promote`, `memory_forget`

This tool uses Memory V2 APIs (no direct filesystem assumptions for memory writes).

### `imap` / `smtp`

Email tools.

Configured in runtime config under `tools.imap` and `tools.smtp`.

### `send_voice`

Synthesize and send voice responses (when voice is enabled and configured).

### `datetime` / `weather`

Utility tools.

## See Also

- [Configuration Guide](CONFIGURATION.md)
- [Skills Guide](SKILLS.md)
- [MCP Integration](MCP.md)
