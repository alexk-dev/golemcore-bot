package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSettingsFacadeTest {

    private RuntimeConfigService runtimeConfigService;
    private RuntimeSettingsFacade facade;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        MemoryPresetService memoryPresetService = mock(MemoryPresetService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        RuntimeSettingsValidator validator = new RuntimeSettingsValidator(
                modelSelectionService,
                new SttProviderRegistry(),
                new TtsProviderRegistry());
        RuntimeSettingsMergeService mergeService = new RuntimeSettingsMergeService();
        facade = new RuntimeSettingsFacade(
                runtimeConfigService,
                preferencesService,
                memoryPresetService,
                validator,
                mergeService);
    }

    @Test
    void shouldRejectManagedHiveMutationBeforePersistingRuntimeConfig() {
        RuntimeConfig current = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(false)
                        .build())
                .build();
        RuntimeConfig incoming = RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder()
                        .enabled(true)
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(current);
        when(runtimeConfigService.isHiveManagedByProperties()).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> facade.updateRuntimeConfig(incoming));

        verify(runtimeConfigService, never()).updateRuntimeConfig(any());
    }
}
