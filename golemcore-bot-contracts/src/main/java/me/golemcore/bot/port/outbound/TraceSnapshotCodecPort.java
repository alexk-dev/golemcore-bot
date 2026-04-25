package me.golemcore.bot.port.outbound;

public interface TraceSnapshotCodecPort {

    byte[] encodeJson(Object payload);

    <T> T decodeJson(String payload, Class<T> targetType);
}
