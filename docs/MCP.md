# MCP Integration

How to attach external tool servers via MCP (Model Context Protocol).

## Overview

MCP lets a skill start a tool server process (stdio transport), discover its tools, and expose them to the LLM alongside native tools.

MCP is enabled/disabled at runtime via `preferences/runtime-config.json`:

```json
{
  "mcp": {
    "enabled": true,
    "defaultStartupTimeout": 30,
    "defaultIdleTimeout": 5
  }
}
```

## Skill Configuration

Declare MCP in a skill's YAML frontmatter:

```yaml
---
name: github-assistant
description: Work with GitHub via MCP
mcp:
  command: npx -y @modelcontextprotocol/server-github
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
  startup_timeout: 30
  idle_timeout: 10
---

Use the available MCP tools to work with GitHub.
```

Notes:

- `command` is required.
- `env` supports `${VAR}` placeholders resolved from skill variables and OS environment.
- `startup_timeout` / `idle_timeout` override runtime defaults for that skill.

## Lifecycle

1. Skill activates.
2. MCP client starts the server process.
3. Bot performs `initialize` and `tools/list` handshake.
4. Each MCP tool is wrapped and exposed as a normal tool.
5. After `idle_timeout` minutes without calls, the server stops.

## Troubleshooting

- Ensure required binaries exist in the runtime environment (e.g., `node`, `npx`).
- Ensure required tokens are available to the MCP server (skill vars or environment).
- Increase `startup_timeout` for slower servers.

## See Also

- [Skills Guide](SKILLS.md)
- [Tools Guide](TOOLS.md)
- [Configuration Guide](CONFIGURATION.md)
