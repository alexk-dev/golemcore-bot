package me.golemcore.bot.client.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import me.golemcore.bot.client.dto.HiveStatusResponse;
import me.golemcore.bot.domain.model.hive.HiveStatusSnapshot;
import org.junit.jupiter.api.Test;

class HiveStatusResponseMapperSupportTest {

    @Test
    void shouldMapHiveStatusSnapshotToResponse() {
        HiveStatusSnapshot snapshot = new HiveStatusSnapshot(
                "CONNECTED",
                true,
                false,
                true,
                true,
                "https://hive.example.com",
                "Builder",
                "lab-a",
                "https://bot.example.com/dashboard",
                true,
                true,
                "golem-1",
                "wss://hive.example.com/control",
                30,
                Instant.parse("2026-03-18T00:00:00Z"),
                Instant.parse("2026-03-18T00:00:05Z"),
                Instant.parse("2026-03-18T00:00:10Z"),
                "CONNECTED",
                Instant.parse("2026-03-18T00:00:00Z"),
                Instant.parse("2026-03-18T00:00:05Z"),
                null,
                "cmd-1",
                "2026-03-18T00:00:04Z",
                7,
                2,
                1,
                3,
                4,
                null,
                null,
                "policy-a",
                11,
                10,
                "SYNCING",
                "digest-1");

        HiveStatusResponse response = HiveStatusResponseMapperSupport.toResponse(snapshot);

        assertEquals("CONNECTED", response.state());
        assertTrue(response.enabled());
        assertFalse(response.managedByProperties());
        assertTrue(response.managedJoinCodeAvailable());
        assertEquals("golem-1", response.golemId());
        assertEquals(Integer.valueOf(11), response.targetPolicyVersion());
        assertEquals("SYNCING", response.policySyncStatus());
    }
}
