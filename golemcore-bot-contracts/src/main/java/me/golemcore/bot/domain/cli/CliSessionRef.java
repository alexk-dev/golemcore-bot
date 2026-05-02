package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * Lightweight reference to a CLI-backed agent session.
 */
public record CliSessionRef(String sessionId,String projectId,String title,@JsonFormat(shape=JsonFormat.Shape.STRING)Instant createdAt,@JsonFormat(shape=JsonFormat.Shape.STRING)Instant lastUsedAt){}
