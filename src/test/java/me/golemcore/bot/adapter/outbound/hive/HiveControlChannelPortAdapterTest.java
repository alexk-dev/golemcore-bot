package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.function.Consumer;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveControlChannelStatusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveControlChannelPortAdapterTest {

    private HiveControlChannelClient hiveControlChannelClient;
    private HiveControlChannelPortAdapter adapter;

    @BeforeEach
    void setUp() {
        hiveControlChannelClient = mock(HiveControlChannelClient.class);
        adapter = new HiveControlChannelPortAdapter(hiveControlChannelClient);
    }

    @Test
    void shouldDelegateConnectAndDisconnect() {
        HiveSessionState sessionState = HiveSessionState.builder().golemId("golem-1").build();
        Consumer<HiveControlCommandEnvelope> consumer = envelope -> {
        };

        adapter.connect(sessionState, consumer);
        adapter.disconnect("stop");

        verify(hiveControlChannelClient).connect(sessionState, consumer);
        verify(hiveControlChannelClient).disconnect("stop");
    }

    @Test
    void shouldReturnDisconnectedSnapshotWhenClientStatusIsMissing() {
        when(hiveControlChannelClient.getStatus()).thenReturn(null);

        HiveControlChannelStatusSnapshot status = adapter.getStatus();

        assertEquals("DISCONNECTED", status.state());
        assertNull(status.connectedAt());
        assertNull(status.lastMessageAt());
        assertNull(status.lastError());
        assertNull(status.lastReceivedCommandId());
        assertEquals(0, status.receivedCommandCount());
    }

    @Test
    void shouldMapStatusToDomainSnapshot() {
        when(hiveControlChannelClient.getStatus()).thenReturn(new HiveControlChannelStatus(
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                Instant.parse("2026-03-18T00:01:00Z"),
                "warn",
                "cmd-1",
                7));

        HiveControlChannelStatusSnapshot status = adapter.getStatus();

        assertEquals("CONNECTED", status.state());
        assertEquals("warn", status.lastError());
        assertEquals("cmd-1", status.lastReceivedCommandId());
        assertEquals(7, status.receivedCommandCount());
    }
}
