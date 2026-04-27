package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RuntimeConfigSectionOwnershipTest {

    @Test
    void shouldDeclareOwnershipForEveryRuntimeConfigSection() {
        assertEquals(RuntimeConfig.ConfigSection.values().length, RuntimeConfigSectionOwnership.all().size());

        for (RuntimeConfig.ConfigSection section : RuntimeConfig.ConfigSection.values()) {
            RuntimeConfigSectionOwnership.SectionOwnership ownership = RuntimeConfigSectionOwnership.ownerOf(section);

            assertEquals(section, ownership.section());
            assertFalse(ownership.ownerService().isBlank());
            assertFalse(ownership.defaultsOwner().isBlank());
            assertFalse(ownership.validatorOwner().isBlank());
            assertFalse(ownership.persistenceOwner().isBlank());
            assertFalse(ownership.queryAdminViewOwner().isBlank());
        }
    }

    @Test
    void shouldAssignKnownBoundedContextOwners() {
        assertEquals("LlmConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.MODEL_ROUTER).ownerService());
        assertEquals("ToolConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.TOOL_LOOP).ownerService());
        assertEquals("HiveConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.HIVE).ownerService());
        assertEquals("SelfEvolvingConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.SELF_EVOLVING).ownerService());
        assertEquals("DelayedActionsConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.DELAYED_ACTIONS).ownerService());
        assertEquals("TelegramConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.TELEGRAM).ownerService());
    }

    @Test
    void shouldKeepSectionOwnershipOutOfTheCompatibilityFacade() {
        for (RuntimeConfigSectionOwnership.SectionOwnership ownership : RuntimeConfigSectionOwnership.all().values()) {
            assertNotEquals("RuntimeConfigService", ownership.ownerService());
        }
    }

    @Test
    void shouldRejectMissingSection() {
        assertThrows(NullPointerException.class, () -> RuntimeConfigSectionOwnership.ownerOf(null));
    }

    @Test
    void shouldReturnIsolatedOwnershipMap() {
        assertNotSame(RuntimeConfigSectionOwnership.all(), RuntimeConfigSectionOwnership.all());
        assertThrows(UnsupportedOperationException.class, () -> RuntimeConfigSectionOwnership.all().clear());

        assertEquals("LlmConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.LLM).ownerService());
    }
}
