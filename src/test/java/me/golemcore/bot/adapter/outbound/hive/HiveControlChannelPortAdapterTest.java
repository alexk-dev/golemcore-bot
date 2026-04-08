package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.HiveControlChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HiveControlChannelPortAdapterTest {

    @Mock
    private HiveControlChannelClient hiveControlChannelClient;

    private HiveControlChannelPortAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HiveControlChannelPortAdapter(hiveControlChannelClient);
    }

    @Test
    void connectShouldDelegateToClient() {
        HiveSessionState sessionState = HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("https://hive.example.com")
                .build();
        Consumer<HiveControlCommandEnvelope> consumer = envelope -> {
        };

        adapter.connect(sessionState, consumer);

        verify(hiveControlChannelClient).connect(same(sessionState), same(consumer));
    }

    @Test
    void disconnectShouldDelegateToClient() {
        adapter.disconnect("manual");

        verify(hiveControlChannelClient).disconnect("manual");
    }

    @Test
    void getStatusShouldReturnDisconnectedWhenClientHasNoStatus() {
        when(hiveControlChannelClient.getStatus()).thenReturn(null);

        HiveControlChannelPort.ControlChannelStatus status = adapter.getStatus();

        assertEquals("DISCONNECTED", status.state());
        assertNull(status.connectedAt());
        assertNull(status.lastMessageAt());
        assertNull(status.lastError());
        assertNull(status.lastReceivedCommandId());
        assertEquals(0, status.receivedCommandCount());
    }

    @Test
    void getStatusShouldMapClientStatus() {
        Instant connectedAt = Instant.parse("2026-04-08T00:00:00Z");
        Instant lastMessageAt = Instant.parse("2026-04-08T00:05:00Z");
        when(hiveControlChannelClient.getStatus()).thenReturn(new HiveControlChannelStatus(
                "CONNECTED",
                connectedAt,
                lastMessageAt,
                "none",
                "cmd-1",
                7));

        HiveControlChannelPort.ControlChannelStatus status = adapter.getStatus();

        assertEquals("CONNECTED", status.state());
        assertEquals(connectedAt, status.connectedAt());
        assertEquals(lastMessageAt, status.lastMessageAt());
        assertEquals("none", status.lastError());
        assertEquals("cmd-1", status.lastReceivedCommandId());
        assertEquals(7, status.receivedCommandCount());
    }
}
