package me.golemcore.bot.application.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RuntimeSettingsValidatorTest {

    private RuntimeSettingsValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RuntimeSettingsValidator(
                mock(ModelSelectionService.class),
                new SttProviderRegistry(),
                new TtsProviderRegistry());
    }

    @Test
    void shouldRejectNullRuntimeConfigDuringFullUpdateValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateRuntimeConfigUpdate(RuntimeConfig.builder().build(), null, false));
    }
}
