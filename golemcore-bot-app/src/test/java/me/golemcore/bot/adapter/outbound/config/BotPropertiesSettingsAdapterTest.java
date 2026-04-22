package me.golemcore.bot.adapter.outbound.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.HiveBootstrapSettingsPort.HiveBootstrapSettings;
import me.golemcore.bot.port.outbound.SelfEvolvingBootstrapSettingsPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import org.junit.jupiter.api.Test;

class BotPropertiesSettingsAdapterTest {

    @Test
    void shouldMapConfiguredSectionsIntoDomainSettings() {
        BotProperties properties = new BotProperties();
        properties.getMemory().setDirectory("mem-store");
        properties.getSkills().setDirectory("skills-dir");
        properties.getSkills().setMarketplaceEnabled(true);
        properties.getSkills().setMarketplaceRepositoryDirectory("/tmp/registry");
        properties.getSkills().setMarketplaceSandboxPath("/tmp/sandbox");
        properties.getSkills().setMarketplaceRepositoryUrl("https://example.com/repo.git");
        properties.getSkills().setMarketplaceBranch("develop");
        properties.getSkills().setMarketplaceApiBaseUrl("https://api.example.com");
        properties.getSkills().setMarketplaceRawBaseUrl("https://raw.example.com");
        properties.getSkills().setMarketplaceRemoteCacheTtl(Duration.ofSeconds(42));
        properties.getPrompts().setEnabled(false);
        properties.getPrompts().setBotName("Golem");
        properties.getPrompts().setCustomVars(Map.of("REGION", "eu"));
        properties.getAutoCompact().setMaxToolResultChars(2048);
        properties.getUpdate().setEnabled(false);
        properties.getUpdate().setUpdatesPath("/var/updates");
        properties.getUpdate().setMaxKeptVersions(7);
        properties.getUpdate().setCheckInterval(Duration.ofMinutes(15));
        properties.getTools().getFilesystem().setWorkspace("/srv/fs");
        properties.getTools().getShell().setWorkspace("/srv/sh");
        properties.getTurn().setMaxLlmCalls(12);
        properties.getTurn().setMaxToolExecutions(34);
        properties.getTurn().setDeadline(Duration.ofMinutes(5));
        properties.getToolLoop().setStopOnToolFailure(true);
        properties.getToolLoop().setStopOnConfirmationDenied(false);
        properties.getToolLoop().setStopOnToolPolicyDenied(true);
        properties.getSelfEvolving().getBootstrap().setEnabled(true);
        properties.getSelfEvolving().getBootstrap().getTactics().setEnabled(true);
        properties.getSelfEvolving().getBootstrap().getTactics().getSearch().setMode("hybrid");
        properties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setProvider("ollama");
        properties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setModel(
                "nomic-embed");
        properties.getSelfEvolving()
                .getBootstrap()
                .getTactics()
                .getSearch()
                .getEmbeddings()
                .getLocal()
                .setAutoInstall(true);
        properties.getSelfEvolving()
                .getBootstrap()
                .getTactics()
                .getSearch()
                .getPersonalization()
                .setEnabled(true);
        properties.getSelfEvolving()
                .getBootstrap()
                .getTactics()
                .getSearch()
                .getNegativeMemory()
                .setEnabled(false);
        properties.getHive().setEnabled(true);
        properties.getHive().setAutoConnectOnStartup(false);
        properties.getHive().setJoinCode("et_demo.secret:https://hive.example.com");
        properties.getHive().setDisplayName("Builder");
        properties.getHive().setHostLabel("lab-a");
        properties.getHive().setDashboardBaseUrl("https://dashboard.example.com");
        properties.getHive().setSsoEnabled(true);
        properties.getHttp().setConnectTimeout(3210L);

        BotPropertiesSettingsAdapter settings = new BotPropertiesSettingsAdapter(properties);

        assertEquals("mem-store", settings.memory().directory());
        assertEquals("skills-dir", settings.skills().directory());
        assertTrue(settings.skills().marketplace().enabled());
        assertEquals("/tmp/registry", settings.skills().marketplace().repositoryDirectory());
        assertEquals("/tmp/sandbox", settings.skills().marketplace().sandboxPath());
        assertEquals("https://example.com/repo.git", settings.skills().marketplace().repositoryUrl());
        assertEquals("develop", settings.skills().marketplace().branch());
        assertEquals("https://api.example.com", settings.skills().marketplace().apiBaseUrl());
        assertEquals("https://raw.example.com", settings.skills().marketplace().rawBaseUrl());
        assertEquals(Duration.ofSeconds(42), settings.skills().marketplace().remoteCacheTtl());
        assertFalse(settings.prompts().enabled());
        assertEquals("Golem", settings.prompts().botName());
        assertEquals(Map.of("REGION", "eu"), settings.prompts().customVars());
        assertEquals(2048, settings.toolExecution().maxToolResultChars());
        assertFalse(settings.update().enabled());
        assertEquals("/var/updates", settings.update().updatesPath());
        assertEquals(7, settings.update().maxKeptVersions());
        assertEquals(Duration.ofMinutes(15), settings.update().checkInterval());
        assertEquals("/srv/fs", settings.workspace().filesystemWorkspace());
        assertEquals("/srv/sh", settings.workspace().shellWorkspace());
        assertEquals(12, settings.turn().maxLlmCalls());
        assertEquals(34, settings.turn().maxToolExecutions());
        assertEquals(Duration.ofMinutes(5), settings.turn().deadline());
        assertTrue(settings.toolLoop().stopOnToolFailure());
        assertFalse(settings.toolLoop().stopOnConfirmationDenied());
        assertTrue(settings.toolLoop().stopOnToolPolicyDenied());
        assertTrue(settings.selfEvolvingBootstrap().enabled());
        assertTrue(settings.selfEvolvingBootstrap().tactics().enabled());
        assertEquals("hybrid", settings.selfEvolvingBootstrap().tactics().search().mode());
        assertEquals(
                "ollama",
                settings.selfEvolvingBootstrap().tactics().search().embeddings().provider());
        assertEquals(
                "nomic-embed",
                settings.selfEvolvingBootstrap().tactics().search().embeddings().model());
        assertTrue(settings.selfEvolvingBootstrap()
                .tactics()
                .search()
                .embeddings()
                .local()
                .autoInstall());
        assertTrue(settings.selfEvolvingBootstrap()
                .tactics()
                .search()
                .personalization()
                .enabled());
        assertFalse(settings.selfEvolvingBootstrap()
                .tactics()
                .search()
                .negativeMemory()
                .enabled());
        HiveBootstrapSettings hiveBootstrapSettings = settings.hiveBootstrapSettings();
        assertTrue(hiveBootstrapSettings.enabled());
        assertFalse(hiveBootstrapSettings.autoConnectOnStartup());
        assertEquals("et_demo.secret:https://hive.example.com", hiveBootstrapSettings.joinCode());
        assertEquals("Builder", hiveBootstrapSettings.displayName());
        assertEquals("lab-a", hiveBootstrapSettings.hostLabel());
        assertEquals("https://dashboard.example.com", hiveBootstrapSettings.dashboardBaseUrl());
        assertTrue(hiveBootstrapSettings.ssoEnabled());
        assertEquals(3210L, settings.http().connectTimeoutMillis());
    }

    @Test
    void shouldFallBackToPortDefaultsWhenPropertiesSectionsAreMissing() {
        BotProperties properties = new BotProperties();
        BotProperties defaultProperties = new BotProperties();
        properties.setPrompts(null);
        properties.setTurn(null);
        properties.setToolLoop(null);
        properties.setSkills(null);
        properties.setUpdate(null);
        properties.setTools(null);
        properties.setSelfEvolving(null);
        properties.setHive(null);
        properties.setHttp(null);

        BotPropertiesSettingsAdapter settings = new BotPropertiesSettingsAdapter(properties);

        assertTrue(settings.prompts().enabled());
        assertEquals("AI Assistant", settings.prompts().botName());
        assertTrue(settings.prompts().customVars().isEmpty());
        assertEquals(ToolRuntimeSettingsPort.defaultTurnSettings(), settings.turn());
        assertEquals(ToolRuntimeSettingsPort.defaultToolLoopSettings(), settings.toolLoop());
        assertFalse(settings.skills().marketplace().enabled());
        assertTrue(settings.skills().marketplace().remoteCacheTtl().compareTo(Duration.ZERO) > 0);
        assertTrue(settings.update().enabled());
        assertEquals(defaultProperties.getUpdate().getUpdatesPath(), settings.update().updatesPath());
        assertEquals(3, settings.update().maxKeptVersions());
        assertEquals(defaultProperties.getTools().getFilesystem().getWorkspace(),
                settings.workspace().filesystemWorkspace());
        assertEquals(defaultProperties.getTools().getShell().getWorkspace(), settings.workspace().shellWorkspace());
        assertEquals(
                new SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings(null, null),
                settings.selfEvolvingBootstrap());
        assertEquals(HiveBootstrapSettings.empty(), settings.hiveBootstrapSettings());
        assertEquals(10000L, settings.http().connectTimeoutMillis());
    }
}
