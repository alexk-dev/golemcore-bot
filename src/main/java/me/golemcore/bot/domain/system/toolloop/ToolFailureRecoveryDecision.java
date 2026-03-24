package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.ToolFailureRecoverability;

/**
 * Decision returned by the tool failure recovery policy for a single failed
 * tool execution.
 */
public record ToolFailureRecoveryDecision(boolean shouldStop,boolean shouldInjectRecoveryHint,String recoveryHint,String fingerprint,ToolFailureRecoverability recoverability){}
