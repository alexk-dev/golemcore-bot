package me.golemcore.bot.domain.cli;

/**
 * Per-run budget overrides accepted by CLI and API clients.
 */
public record RunBudget(Integer maxLlmCalls,Integer maxToolExecutions,String timeout){}
