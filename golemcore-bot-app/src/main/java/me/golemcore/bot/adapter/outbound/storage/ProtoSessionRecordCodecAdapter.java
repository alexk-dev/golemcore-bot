package me.golemcore.bot.adapter.outbound.storage;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.port.outbound.SessionRecordCodecPort;
import me.golemcore.bot.proto.session.v1.AgentSessionRecord;
import org.springframework.stereotype.Component;

@Component
public class ProtoSessionRecordCodecAdapter implements SessionRecordCodecPort {

    private final SessionProtoMapperSupport sessionProtoMapperSupport = new SessionProtoMapperSupport();

    @Override
    public byte[] encode(AgentSession session) {
        return sessionProtoMapperSupport.toProto(session).toByteArray();
    }

    @Override
    public AgentSession decode(byte[] bytes) {
        try {
            return sessionProtoMapperSupport.fromProto(AgentSessionRecord.parseFrom(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to decode persisted session record", exception);
        }
    }
}
