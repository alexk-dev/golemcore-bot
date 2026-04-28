package me.golemcore.bot.domain.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.hive.HiveMetadataSupport;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import me.golemcore.bot.port.outbound.RuntimeEventPublishPort;
import me.golemcore.bot.port.outbound.SessionPort;

@Slf4j
final class StopRequestController {

    private final SessionPort sessionPort;
    private final RuntimeEventService runtimeEventService;
    private final RuntimeEventPublishPort runtimeEventPublishPort;

    StopRequestController(
            SessionPort sessionPort,
            RuntimeEventService runtimeEventService,
            RuntimeEventPublishPort runtimeEventPublishPort) {
        this.sessionPort = sessionPort;
        this.runtimeEventService = runtimeEventService;
        this.runtimeEventPublishPort = runtimeEventPublishPort;
    }

    void markInterruptRequested(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return;
        }

        Map<String, Object> metadata = session.getMetadata();
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            session.setMetadata(metadata);
        }
        metadata.put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);
        sessionPort.save(session);
    }

    void clearInterruptRequested(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return;
        }

        Map<String, Object> metadata = session.getMetadata();
        if (metadata == null || !metadata.containsKey(ContextAttributes.TURN_INTERRUPT_REQUESTED)) {
            return;
        }
        metadata.remove(ContextAttributes.TURN_INTERRUPT_REQUESTED);
        sessionPort.save(session);
    }

    boolean isInterruptRequested(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return false;
        }
        Map<String, Object> metadata = session.getMetadata();
        return metadata != null && Boolean.TRUE.equals(metadata.get(ContextAttributes.TURN_INTERRUPT_REQUESTED));
    }

    void publishStopRequestedEvent(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return;
        }
        runtimeEventService.emitForSession(session, RuntimeEventType.TURN_INTERRUPT_REQUESTED,
                Map.of("source", HiveRuntimeContracts.TURN_INTERRUPT_SOURCE_COMMAND_STOP));
    }

    void publishHiveInterruptedFallback(Message inbound) {
        if (inbound == null || inbound.getChannelType() == null
                || !ChannelTypes.HIVE.equalsIgnoreCase(inbound.getChannelType())) {
            return;
        }

        Map<String, Object> metadata = buildHiveMetadata(inbound);
        if (!metadata.containsKey(ContextAttributes.HIVE_THREAD_ID)) {
            return;
        }

        AgentSession session = sessionPort.getOrCreate(inbound.getChannelType(), inbound.getChatId());
        RuntimeEvent runtimeEvent = runtimeEventService.emitForSession(session, RuntimeEventType.TURN_FINISHED,
                Map.of("reason", HiveRuntimeContracts.USER_INTERRUPT_REASON));
        try {
            runtimeEventPublishPort.publishRuntimeEvents(List.of(runtimeEvent), metadata);
        } catch (RuntimeException exception) {
            log.warn("[Hive] Failed to publish interruption fallback: {}", exception.getMessage());
        }
    }

    private Map<String, Object> buildHiveMetadata(Message inbound) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> inboundMetadata = inbound.getMetadata();
        HiveMetadataSupport.copyMetadataMap(inboundMetadata, metadata);
        String threadId = HiveMetadataSupport.readString(metadata, ContextAttributes.HIVE_THREAD_ID);
        if (threadId == null || threadId.isBlank()) {
            threadId = inbound.getChatId();
        }
        HiveMetadataSupport.putIfPresent(metadata, ContextAttributes.HIVE_THREAD_ID, threadId);
        return metadata;
    }
}
