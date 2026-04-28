package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeConfigSectionSchemaRegistryTest {

    @Test
    void shouldDeclareVersionedSchemaForEveryRuntimeConfigSection() {
        assertEquals(RuntimeConfig.ConfigSection.values().length, RuntimeConfigSectionSchemaRegistry.all().size());

        for (RuntimeConfig.ConfigSection section : RuntimeConfig.ConfigSection.values()) {
            RuntimeConfigSectionSchemaRegistry.SectionSchema schema = RuntimeConfigSectionSchemaRegistry
                    .schemaOf(section);

            assertEquals(section, schema.section());
            assertEquals(1, schema.currentVersion());
            assertEquals("runtime-config." + section.getFileId() + ".v1", schema.schemaId());
            assertFalse(schema.migrationOwner().isBlank());
        }
    }

    @Test
    void shouldKeepCurrentVersionMigrationAsStableNoOp() {
        Map<String, Object> payload = Map.of("enabled", true);

        Object migrated = RuntimeConfigSectionSchemaRegistry.migrate(RuntimeConfig.ConfigSection.MEMORY, 1, payload);

        assertSame(payload, migrated);
    }

    @Test
    void shouldRejectInvalidMigrationVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigSectionSchemaRegistry.migrate(RuntimeConfig.ConfigSection.MEMORY, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigSectionSchemaRegistry.migrate(RuntimeConfig.ConfigSection.MEMORY, 2, Map.of()));
    }

    @Test
    void shouldRejectMissingSection() {
        assertThrows(NullPointerException.class, () -> RuntimeConfigSectionSchemaRegistry.schemaOf(null));
        assertThrows(NullPointerException.class, () -> RuntimeConfigSectionSchemaRegistry.migrate(null, 1, Map.of()));
    }

    @Test
    void shouldReturnIsolatedSchemaMap() {
        assertNotSame(RuntimeConfigSectionSchemaRegistry.all(), RuntimeConfigSectionSchemaRegistry.all());
        assertThrows(UnsupportedOperationException.class, () -> RuntimeConfigSectionSchemaRegistry.all().clear());
    }
}
