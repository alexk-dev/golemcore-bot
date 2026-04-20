package me.golemcore.bot.adapter.inbound.web.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import me.golemcore.bot.client.mapper.RuntimeSettingsWebMapper;
import org.junit.jupiter.api.Test;

class RuntimeSettingsWebMapperTest {

    private final RuntimeSettingsWebMapper mapper = new RuntimeSettingsWebMapper();

    @Test
    void shouldInstantiateReusableRuntimeSettingsMapper() {
        assertNotNull(mapper);
    }
}
