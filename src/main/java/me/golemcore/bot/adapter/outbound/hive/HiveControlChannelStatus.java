package me.golemcore.bot.adapter.outbound.hive;

import java.time.Instant;

public record HiveControlChannelStatus(String state,Instant connectedAt,Instant lastMessageAt,String lastError,String lastReceivedCommandId,int receivedCommandCount){

public static HiveControlChannelStatus disconnected(){return new HiveControlChannelStatus("DISCONNECTED",null,null,null,null,0);}}
