package me.golemcore.bot.domain.model.hive;

public record HiveOutboxSummary(int pendingBatchCount,int pendingEventCount,String lastError){}
