# CLAUDE.md — GolemCore Bot Client Module

This module contains reusable backend-facing abstractions for GolemCore Bot clients.

## What belongs here

- dashboard and CLI request/response DTOs
- client-oriented mappers between reusable DTOs and shared contracts
- backend facade/service abstractions intended to be reused by multiple clients
- transport-agnostic payload models shared by dashboard, CLI, or future clients

## Rules

1. Prefer putting reusable dashboard/backend API models here instead of `golemcore-bot-app`.
2. Keep this module transport-agnostic where practical: avoid controllers, servlet/webflux endpoints, or runtime launcher code.
3. If a type is a stable cross-module contract used outside this module, move it to `golemcore-bot-contracts` instead.
4. `golemcore-bot-app` should depend on this module for reusable client DTOs/mappers, not redefine them locally.
