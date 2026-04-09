package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;

class BotPropertiesHiveBootstrapSettingsAdapterTest {

    @Test
    void shouldExposeHiveBootstrapSettingsFromBotProperties() {
        BotProperties botProperties = new BotProperties();
        botProperties.getHive().setEnabled(true);
        botProperties.getHive().setAutoConnectOnStartup(false);
        botProperties.getHive().setJoinCode("et_demo.secret:https://hive.example.com");
        botProperties.getHive().setDisplayName("Builder");
        botProperties.getHive().setHostLabel("lab-a");

        BotPropertiesHiveBootstrapSettingsAdapter adapter = new BotPropertiesHiveBootstrapSettingsAdapter(
                botProperties);

        assertEquals(true, adapter.enabled());
        assertEquals(false, adapter.autoConnectOnStartup());
        assertEquals("et_demo.secret:https://hive.example.com", adapter.joinCode());
        assertEquals("Builder", adapter.displayName());
        assertEquals("lab-a", adapter.hostLabel());
    }
}
