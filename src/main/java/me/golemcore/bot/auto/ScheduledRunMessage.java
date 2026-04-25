package me.golemcore.bot.auto;

import me.golemcore.bot.domain.model.AutoRunKind;

/**
 * Immutable scheduled run payload shared between message construction and
 * execution.
 */
record ScheduledRunMessage(String content,AutoRunKind runKind,String scheduledTaskId,String goalId,String taskId,String goalTitle,String taskTitle,boolean reflectionActive,String reflectionTier,boolean reflectionTierPriority,boolean resetTaskBeforeRun){

static ScheduledRunMessage forGoal(String content,String goalId,String goalTitle,String reflectionTier,boolean reflectionTierPriority){return new ScheduledRunMessage(content,AutoRunKind.GOAL_RUN,null,goalId,null,goalTitle,null,false,reflectionTier,reflectionTierPriority,false);}

static ScheduledRunMessage forTask(String content,String goalId,String taskId,String goalTitle,String taskTitle,String reflectionTier,boolean reflectionTierPriority,boolean resetTaskBeforeRun){return new ScheduledRunMessage(content,AutoRunKind.GOAL_RUN,null,goalId,taskId,goalTitle,taskTitle,false,reflectionTier,reflectionTierPriority,resetTaskBeforeRun);}

static ScheduledRunMessage forScheduledTask(String content,String scheduledTaskId,String taskTitle,String reflectionTier,boolean reflectionTierPriority){return new ScheduledRunMessage(content,AutoRunKind.SCHEDULED_TASK_RUN,scheduledTaskId,null,null,null,taskTitle,false,reflectionTier,reflectionTierPriority,false);}

static ScheduledRunMessage forReflection(ScheduledRunMessage source,String content,String reflectionTier,boolean reflectionTierPriority){return new ScheduledRunMessage(content,source.runKind(),source.scheduledTaskId(),source.goalId(),source.taskId(),source.goalTitle(),source.taskTitle(),true,reflectionTier,reflectionTierPriority,false);}}
