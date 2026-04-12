package me.golemcore.bot.domain.model;

import java.time.Instant;
import lombok.Builder;

@Builder public record HiveInspectionResponse(String requestId,String threadId,String cardId,String runId,String golemId,String operation,boolean success,String errorCode,String errorMessage,Object payload,Instant createdAt){}
