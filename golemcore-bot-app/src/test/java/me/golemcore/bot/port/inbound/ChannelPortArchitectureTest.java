package me.golemcore.bot.port.inbound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChannelPortArchitectureTest {

    private static final Path CHANNEL_PORT_SOURCE = Path.of(
            "../golemcore-bot-contracts/src/main/java/me/golemcore/bot/port/channel/ChannelPort.java");
    private static final Path INBOUND_CHANNEL_PORT_SOURCE = Path.of(
            "../golemcore-bot-contracts/src/main/java/me/golemcore/bot/port/inbound/InboundChannelPort.java");
    private static final Path CHANNEL_RUNTIME_PORT_SOURCE = Path.of(
            "../golemcore-bot-contracts/src/main/java/me/golemcore/bot/port/outbound/ChannelRuntimePort.java");
    private static final Path RESPONSE_ROUTING_SOURCE = Path.of(
            "src/main/java/me/golemcore/bot/domain/system/ResponseRoutingSystem.java");

    @Test
    void channelPortShouldBeOnlyATransitionalCombinedContract() {
        String source = readSource(CHANNEL_PORT_SOURCE);

        assertTrue(source.contains("extends InboundChannelPort, ChannelDeliveryPort"),
                "ChannelPort should remain a transitional combined contract over separate inbound and outbound ports");
        assertTrue(source.contains("package me.golemcore.bot.port.channel;"),
                "The transitional combined contract should live in a neutral port namespace");
        assertFalse(source.contains("void start();") || source.contains("CompletableFuture<Void> sendVoice("),
                "ChannelPort should not keep duplicating inbound and outbound method declarations once split contracts exist");
    }

    @Test
    void inboundChannelPortShouldNotDependOnDeliveryPort() {
        String source = readSource(INBOUND_CHANNEL_PORT_SOURCE);

        assertFalse(source.contains("ChannelDeliveryPort"),
                "InboundChannelPort should not depend on outbound delivery contracts");
    }

    @Test
    void channelRuntimePortShouldResolveDeliveryPortsOnly() {
        String source = readSource(CHANNEL_RUNTIME_PORT_SOURCE);

        assertTrue(source.contains("ChannelDeliveryPort"),
                "ChannelRuntimePort should expose only delivery-facing ports");
        assertFalse(source.contains("ChannelPort"),
                "ChannelRuntimePort should not expose the combined ChannelPort once split contracts exist");
    }

    @Test
    void responseRoutingSystemShouldDependOnDeliveryPortRatherThanCombinedChannelPort() {
        String source = readSource(RESPONSE_ROUTING_SOURCE);

        assertTrue(source.contains("ChannelDeliveryPort"),
                "ResponseRoutingSystem should depend on outbound delivery contracts after transport split");
        assertFalse(source.contains("import me.golemcore.bot.port.channel.ChannelPort;"),
                "ResponseRoutingSystem should not depend on the combined ChannelPort after transport split");
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read source: " + path, exception);
        }
    }
}
