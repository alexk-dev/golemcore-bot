package me.golemcore.bot.domain.model.hive;

import java.time.Instant;

public record HiveControlChannelStatusSnapshot(String state,Instant connectedAt,Instant lastMessageAt,String lastError,String lastReceivedCommandId,int receivedCommandCount){

public static HiveControlChannelStatusSnapshot disconnected(){return new HiveControlChannelStatusSnapshot("DISCONNECTED",null,null,null,null,0);}}
