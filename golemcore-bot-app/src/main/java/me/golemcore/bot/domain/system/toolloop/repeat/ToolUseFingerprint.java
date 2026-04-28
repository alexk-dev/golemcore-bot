package me.golemcore.bot.domain.system.toolloop.repeat;

/**
 * Stable, secret-safe identity for a tool call's semantic arguments.
 */
public record ToolUseFingerprint(String toolName,ToolUseCategory category,String canonicalArgumentsHash,String stableKey,String debugArguments){}
