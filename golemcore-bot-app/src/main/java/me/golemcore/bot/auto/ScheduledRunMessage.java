package me.golemcore.bot.auto;

import me.golemcore.bot.domain.model.AutoRunKind;

/**
 * Immutable scheduled run payload shared between message construction and
 * execution.
 */
record ScheduledRunMessage(String content,AutoRunKind runKind,String goalId,String taskId,String goalTitle,String taskTitle,boolean reflectionActive,String reflectionTier,boolean reflectionTierPriority,boolean resetTaskBeforeRun){}
