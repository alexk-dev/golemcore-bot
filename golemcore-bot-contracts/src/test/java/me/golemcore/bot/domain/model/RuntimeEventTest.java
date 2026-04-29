package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeEventTest {

    @Test
    void shouldDefaultToCurrentSchemaVersion() {
        RuntimeEvent event = RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .build();

        assertEquals(RuntimeEvent.SCHEMA_VERSION, event.schemaVersion());
    }
}
