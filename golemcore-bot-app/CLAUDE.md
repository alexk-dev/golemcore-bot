# CLAUDE.md — GolemCore Bot App Module

This module contains the runnable Spring Boot application and all runtime implementation code.

## Rules

1. Shared contracts must not be added here.
2. New ports, shared domain models, contract-style component interfaces, and lightweight cross-module views must go to `golemcore-bot-contracts`.
3. This module should depend on `golemcore-bot-contracts`, not redefine shared contracts locally.
4. If a type is used as a cross-module boundary, move it to `golemcore-bot-contracts` instead of duplicating it here.
