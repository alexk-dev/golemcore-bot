package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.domain.extensions.LegacyPluginConfigurationMigrationService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.plugin.runtime.PluginManager;
import me.golemcore.bot.port.channel.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoConfigurationTest {

    @Mock
    private RuntimeConfigService runtimeConfigService;
    @Mock
    private LegacyPluginConfigurationMigrationService legacyPluginConfigurationMigrationService;
    @Mock
    private PluginManager pluginManager;
    @Mock
    private ChannelRegistry channelRegistry;
    @Mock
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    @Mock
    private ObjectProvider<GitProperties> gitPropertiesProvider;
    @Mock
    private ChannelPort channelPort;
    @Mock
    private ChannelPort telegramChannel;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(runtimeConfigService.getBalancedModel()).thenReturn("openai/gpt-5.1");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(gitPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(channelRegistry.getAll()).thenReturn(List.of());
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(false);
    }

    @Test
    void shouldMigrateLegacyConfigAndReloadPluginsOnInit() {
        AutoConfiguration autoConfiguration = new AutoConfiguration(
                new BotProperties(),
                channelRegistry,
                runtimeConfigService,
                legacyPluginConfigurationMigrationService,
                pluginManager,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(legacyPluginConfigurationMigrationService).migrateIfNeeded();
        verify(pluginManager).reloadAll();
    }

    @Test
    void shouldReloadPluginsBeforeStartingChannels() {
        BotProperties properties = new BotProperties();
        BotProperties.ChannelProperties webhookProperties = new BotProperties.ChannelProperties();
        webhookProperties.setEnabled(true);
        properties.getChannels().put("webhook", webhookProperties);
        when(channelPort.getChannelType()).thenReturn("webhook");
        when(channelRegistry.getAll()).thenReturn(List.of(channelPort));

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelRegistry,
                runtimeConfigService,
                legacyPluginConfigurationMigrationService,
                pluginManager,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        org.mockito.InOrder inOrder = inOrder(
                legacyPluginConfigurationMigrationService,
                pluginManager,
                channelPort);
        inOrder.verify(legacyPluginConfigurationMigrationService).migrateIfNeeded();
        inOrder.verify(pluginManager).reloadAll();
        inOrder.verify(channelPort).start();
    }

    @Test
    void shouldStartEnabledNonTelegramChannelsOnInit() {
        BotProperties properties = new BotProperties();
        BotProperties.ChannelProperties webhookProperties = new BotProperties.ChannelProperties();
        webhookProperties.setEnabled(true);
        properties.getChannels().put("webhook", webhookProperties);
        when(channelPort.getChannelType()).thenReturn("webhook");
        when(channelRegistry.getAll()).thenReturn(List.of(channelPort));

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelRegistry,
                runtimeConfigService,
                legacyPluginConfigurationMigrationService,
                pluginManager,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(channelPort).start();
    }

    @Test
    void shouldStartTelegramChannelWhenRuntimeConfigEnablesIt() {
        when(telegramChannel.getChannelType()).thenReturn("telegram");
        when(channelRegistry.getAll()).thenReturn(List.of(telegramChannel));
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                new BotProperties(),
                channelRegistry,
                runtimeConfigService,
                legacyPluginConfigurationMigrationService,
                pluginManager,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(telegramChannel).start();
    }

    @Test
    void shouldNotStartTelegramChannelWhenOnlyPropertiesEnableIt() {
        BotProperties properties = new BotProperties();
        BotProperties.ChannelProperties telegramProperties = new BotProperties.ChannelProperties();
        telegramProperties.setEnabled(true);
        properties.getChannels().put("telegram", telegramProperties);
        when(telegramChannel.getChannelType()).thenReturn("telegram");
        when(channelRegistry.getAll()).thenReturn(List.of(telegramChannel));
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(false);

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelRegistry,
                runtimeConfigService,
                legacyPluginConfigurationMigrationService,
                pluginManager,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(telegramChannel, never()).start();
    }

    @Test
    void shouldNotStartDisabledNonTelegramChannelsOnInit() {
        when(channelPort.getChannelType()).thenReturn("webhook");
        when(channelRegistry.getAll()).thenReturn(List.of(channelPort));

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                new BotProperties(),
                channelRegistry,
                runtimeConfigService,
                legacyPluginConfigurationMigrationService,
                pluginManager,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(channelPort, never()).start();
    }
}
