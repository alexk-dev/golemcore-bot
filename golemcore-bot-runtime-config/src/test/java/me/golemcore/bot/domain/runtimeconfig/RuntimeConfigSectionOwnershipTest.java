package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("SessionRuntimeConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.TOOL_LOOP).ownerService());
        assertEquals("ResilienceConfigService",
                RuntimeConfigSectionOwnership.ownerOf(RuntimeConfig.ConfigSection.RESILIENCE).ownerService());
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
    void shouldBackDeclaredOwnersWithConcreteSectionServices() throws ClassNotFoundException {
        RuntimeConfigNormalizer normalizer = new RuntimeConfigNormalizer();

        for (RuntimeConfigSectionOwnership.SectionOwnership ownership : RuntimeConfigSectionOwnership.all().values()) {
            Class.forName("me.golemcore.bot.domain.runtimeconfig." + ownership.ownerService());
            assertTrue(normalizer.hasSectionService(ownership.ownerService()),
                    () -> ownership.ownerService() + " must be registered in RuntimeConfigNormalizer");
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
