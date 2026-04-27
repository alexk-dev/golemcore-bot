package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Version registry for runtime config section payloads.
 */
public final class RuntimeConfigSectionSchemaRegistry {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Map<RuntimeConfig.ConfigSection, SectionSchema> SCHEMAS = buildSchemas();

    private RuntimeConfigSectionSchemaRegistry() {
    }

    public static SectionSchema schemaOf(RuntimeConfig.ConfigSection section) {
        return SCHEMAS.get(Objects.requireNonNull(section, "section must not be null"));
    }

    public static Map<RuntimeConfig.ConfigSection, SectionSchema> all() {
        return Collections.unmodifiableMap(new EnumMap<>(SCHEMAS));
    }

    public static Object migrate(RuntimeConfig.ConfigSection section, int sourceVersion, Object payload) {
        SectionSchema schema = schemaOf(section);
        if (sourceVersion < 1) {
            throw new IllegalArgumentException("sourceVersion must be positive");
        }
        if (sourceVersion > schema.currentVersion()) {
            throw new IllegalArgumentException("sourceVersion must not be newer than current schema version");
        }
        return payload;
    }

    private static Map<RuntimeConfig.ConfigSection, SectionSchema> buildSchemas() {
        Map<RuntimeConfig.ConfigSection, SectionSchema> schemas = new EnumMap<>(RuntimeConfig.ConfigSection.class);
        for (RuntimeConfig.ConfigSection section : RuntimeConfig.ConfigSection.values()) {
            RuntimeConfigSectionOwnership.SectionOwnership ownership = RuntimeConfigSectionOwnership.ownerOf(section);
            schemas.put(section, new SectionSchema(section, CURRENT_SCHEMA_VERSION,
                    "runtime-config." + section.getFileId() + ".v" + CURRENT_SCHEMA_VERSION,
                    ownership.ownerService() + ".migration"));
        }
        return Map.copyOf(schemas);
    }

    public record SectionSchema(RuntimeConfig.ConfigSection section, int currentVersion, String schemaId,
            String migrationOwner) {

        public SectionSchema {
            Objects.requireNonNull(section, "section must not be null");
            if (currentVersion < 1) {
                throw new IllegalArgumentException("currentVersion must be positive");
            }
            requireText(schemaId, "schemaId");
            requireText(migrationOwner, "migrationOwner");
        }

        private static void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
