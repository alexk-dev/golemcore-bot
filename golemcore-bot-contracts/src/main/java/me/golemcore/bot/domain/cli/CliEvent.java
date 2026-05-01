package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable event envelope emitted to CLI clients.
 */
public record CliEvent(String schemaVersion,String eventId,long sequence,CliEventType type,String runId,String sessionId,String projectId,String traceId,String parentEventId,String correlationId,@JsonFormat(shape=JsonFormat.Shape.STRING)Instant timestamp,CliEventSeverity severity,Map<String,Object>payload){

public static final String SCHEMA_VERSION="cli-event/v1";

public CliEvent{requireNonBlank(schemaVersion,"schemaVersion");requireNonBlank(eventId,"eventId");if(sequence<0){throw new IllegalArgumentException("sequence must be non-negative");}Objects.requireNonNull(type,"type");Objects.requireNonNull(timestamp,"timestamp");Objects.requireNonNull(severity,"severity");payload=CliContractCollections.copyObjectMap(payload);}

public CliEvent(CliEventType type,String runId,String sessionId,String traceId,Instant timestamp,CliEventSeverity severity,Map<String,Object>payload){this(SCHEMA_VERSION,defaultEventId(type,runId),0,type,runId,sessionId,null,traceId,null,null,timestamp,severity,payload);}

private static String defaultEventId(CliEventType type,String runId){String typeValue=type==null?"unknown":type.wireValue();String runValue=runId==null?"run":runId;return"evt_"+runValue+"_"+typeValue.replace('.','_');}

private static void requireNonBlank(String value,String fieldName){if(value==null||value.isBlank()){throw new IllegalArgumentException(fieldName+" must not be blank");}}}
