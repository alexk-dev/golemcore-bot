package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HiveBootstrapConfigSynchronizerTest {

    private HiveBootstrapSettingsPort hiveBootstrapSettingsPort;
    private RuntimeConfigService runtimeConfigService;
    private HiveBootstrapConfigSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        hiveBootstrapSettingsPort = mock(HiveBootstrapSettingsPort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(hiveBootstrapSettingsPort.enabled()).thenReturn(null);
        when(hiveBootstrapSettingsPort.autoConnectOnStartup()).thenReturn(null);
        when(hiveBootstrapSettingsPort.joinCode()).thenReturn(null);
        when(hiveBootstrapSettingsPort.displayName()).thenReturn(null);
        when(hiveBootstrapSettingsPort.hostLabel()).thenReturn(null);
        when(hiveBootstrapSettingsPort.dashboardBaseUrl()).thenReturn(null);
        when(hiveBootstrapSettingsPort.ssoEnabled()).thenReturn(null);
        synchronizer = new HiveBootstrapConfigSynchronizer(hiveBootstrapSettingsPort, runtimeConfigService);
    }

    @Test
    void shouldMaterializeManagedHiveConfigFromBootstrapProperties() {
        when(hiveBootstrapSettingsPort.joinCode()).thenReturn("et_demo.secret:https://hive.example.com/");
        when(hiveBootstrapSettingsPort.displayName()).thenReturn("Build Runner");
        when(hiveBootstrapSettingsPort.hostLabel()).thenReturn("lab-a");
        when(hiveBootstrapSettingsPort.dashboardBaseUrl()).thenReturn("https://bot.example.com/dashboard");
        when(hiveBootstrapSettingsPort.ssoEnabled()).thenReturn(false);
        when(hiveBootstrapSettingsPort.autoConnectOnStartup()).thenReturn(true);

        RuntimeConfig runtimeConfig = RuntimeConfig.builder().build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        synchronizer.synchronize();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
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
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);

        synchronizer.synchronize();

        ArgumentCaptor<RuntimeConfig> captor = ArgumentCaptor.forClass(RuntimeConfig.class);
        verify(runtimeConfigService).updateRuntimeConfig(captor.capture());
        RuntimeConfig.HiveConfig hiveConfig = captor.getValue().getHive();
        assertTrue(hiveConfig.getEnabled());
        assertEquals("https://hive.example.com", hiveConfig.getServerUrl());
        assertFalse(hiveConfig.getManagedByProperties());
    }
}
