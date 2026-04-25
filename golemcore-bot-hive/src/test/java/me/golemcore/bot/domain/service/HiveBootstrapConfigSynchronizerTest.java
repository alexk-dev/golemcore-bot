package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort.HiveBootstrapSettings;
import me.golemcore.bot.port.outbound.RuntimeConfigAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HiveBootstrapConfigSynchronizerTest {

    private HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private RuntimeConfigAdminPort runtimeConfigAdminPort;
    private HiveBootstrapConfigSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        hiveBootstrapSettingsPort = mock(HiveBootstrapSettingsPort.class);
        runtimeConfigAdminPort = mock(RuntimeConfigAdminPort.class);
        when(hiveBootstrapSettingsPort.hiveBootstrapSettings()).thenReturn(HiveBootstrapSettings.empty());
        synchronizer = new HiveBootstrapConfigSynchronizer(hiveBootstrapSettingsPort, runtimeConfigAdminPort);
    }

    @Test
    void shouldMaterializeManagedHiveConfigFromBootstrapProperties() {
        when(hiveBootstrapSettingsPort.hiveBootstrapSettings()).thenReturn(new HiveBootstrapSettings(
                null,
                true,
                "et_demo.secret:https://hive.example.com/",
                "Build Runner",
                "lab-a",
                "https://bot.example.com/dashboard",
                false));

        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        when(runtimeConfigAdminPort.getRuntimeConfig()).thenReturn(runtimeConfig);

        synchronizer.synchronize();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigAdminPort).updateRuntimeConfig(captor.capture());
        RuntimeConfig.HiveConfig hiveConfig = captor.getValue().getHive();
        assertTrue(hiveConfig.getEnabled());
        assertEquals("https://hive.example.com", hiveConfig.getServerUrl());
        assertEquals("Build Runner", hiveConfig.getDisplayName());
        assertEquals("lab-a", hiveConfig.getHostLabel());
        assertEquals("https://bot.example.com/dashboard", hiveConfig.getDashboardBaseUrl());
        assertFalse(hiveConfig.getSsoEnabled());
        assertTrue(hiveConfig.getAutoConnect());
        assertTrue(hiveConfig.getManagedByProperties());
    }

    @Test
    void shouldClearManagedFlagWhenBootstrapOverridesDisappear() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(true)
                        .serverUrl("https://hive.example.com")
                        .managedByProperties(true)
                        .build())
                .build();
        when(runtimeConfigAdminPort.getRuntimeConfig()).thenReturn(runtimeConfig);

        synchronizer.synchronize();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigAdminPort).updateRuntimeConfig(captor.capture());
        RuntimeConfig.HiveConfig hiveConfig = captor.getValue().getHive();
        assertTrue(hiveConfig.getEnabled());
        assertEquals("https://hive.example.com", hiveConfig.getServerUrl());
        assertFalse(hiveConfig.getManagedByProperties());
    }
}
