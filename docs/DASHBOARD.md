# Dashboard Guide

How to use the built-in web dashboard for chat and configuration.

## Access

- URL: `http://localhost:8080/dashboard`
- WebSocket chat endpoint (used by the UI): `/ws/chat?token=...`

## Authentication

The dashboard uses a single admin account.

- Username: `admin`
- Credentials file (persisted in workspace): `preferences/admin.json`

### First Run Password

On first startup (when `preferences/admin.json` does not exist), the bot generates a temporary admin password and prints it in the application logs.

Look for a log block like:

```
DASHBOARD TEMPORARY PASSWORD (change after first login!)
Password: <generated>
```

After logging in, change it in the dashboard.

### Provide a Known Password (Optional)

If you want a preconfigured password (no temporary password in logs), set a **BCrypt hash** via Spring properties:

- Property: `bot.dashboard.admin-password-hash`
- Env var (Spring relaxed binding): `BOT_DASHBOARD_ADMIN_PASSWORD_HASH`

The value must be a BCrypt hash (not a plaintext password).

## Configure The Bot

Most settings are stored in the workspace runtime config file and are editable from the dashboard:

- Runtime config file: `preferences/runtime-config.json`
- API endpoints (used by the UI): `GET /api/settings/runtime`, `PUT /api/settings/runtime`

Typical first setup:

1. Set LLM provider API keys in runtime config (`llm.providers.*.apiKey`).
2. Choose tier models in runtime config (`modelRouter.*Model`).
3. (Optional) Enable Telegram and paste bot token (`telegram.enabled`, `telegram.token`).

## MFA (Optional)

The dashboard supports TOTP MFA for the admin account.

- Status: `GET /api/auth/mfa-status`
- Setup: `POST /api/auth/mfa/setup`
- Enable: `POST /api/auth/mfa/enable`
- Disable: `POST /api/auth/mfa/disable`
