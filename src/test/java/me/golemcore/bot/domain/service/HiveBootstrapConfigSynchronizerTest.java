package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HiveBootstrapConfigSynchronizerTest {

    private BotProperties botProperties;
    private RuntimeConfigService runtimeConfigService;
    private HiveBootstrapConfigSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        runtimeConfigService = mock(RuntimeConfigService.class);
        synchronizer = new HiveBootstrapConfigSynchronizer(botProperties, runtimeConfigService);
    }

    @Test
    void shouldMaterializeManagedHiveConfigFromBootstrapProperties() {
        botProperties.getHive().setJoinCode("et_demo.secret:https://hive.example.com/");
        botProperties.getHive().setDisplayName("Build Runner");
        botProperties.getHive().setHostLabel("lab-a");
        botProperties.getHive().setAutoConnectOnStartup(true);

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
