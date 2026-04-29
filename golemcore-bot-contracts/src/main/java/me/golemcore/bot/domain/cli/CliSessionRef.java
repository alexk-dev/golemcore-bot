package me.golemcore.bot.domain.cli;

import java.time.Instant;

/**
 * Lightweight reference to a CLI-backed agent session.
 */
public record CliSessionRef(String sessionId,String projectId,String title,Instant createdAt,Instant lastUsedAt){}
