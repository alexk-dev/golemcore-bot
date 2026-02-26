package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelCatalogPort;
import me.golemcore.bot.plugin.builtin.browser.adapter.PlaywrightDriverBundleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoConfigurationTest {

    @Mock
    private ChannelCatalogPort channelCatalogPort;
    @Mock
    private RuntimeConfigService runtimeConfigService;
    @Mock
    private PlaywrightDriverBundleService playwrightDriverBundleService;
    @Mock
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    @Mock
    private ObjectProvider<GitProperties> gitPropertiesProvider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(channelCatalogPort.getAllChannels()).thenReturn(List.of());
        when(runtimeConfigService.getBalancedModel()).thenReturn("openai/gpt-5.1");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(gitPropertiesProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    void shouldPreparePlaywrightDriverOnInitWhenBrowserEnabled() {
        BotProperties properties = new BotProperties();
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(true);
        when(playwrightDriverBundleService.ensureDriverReady()).thenReturn(Path.of("/tmp/playwright-driver"));

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelCatalogPort,
                runtimeConfigService,
                playwrightDriverBundleService,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(playwrightDriverBundleService).ensureDriverReady();
    }

    @Test
    void shouldSkipPlaywrightDriverPreparationOnInitWhenBrowserDisabled() {
        BotProperties properties = new BotProperties();
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(false);

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelCatalogPort,
                runtimeConfigService,
                playwrightDriverBundleService,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(playwrightDriverBundleService, never()).ensureDriverReady();
    }

    @Test
    void shouldContinueInitWhenPlaywrightDriverPreparationFails() {
        BotProperties properties = new BotProperties();
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(true);
        when(playwrightDriverBundleService.ensureDriverReady()).thenThrow(new IllegalStateException("download failed"));

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelCatalogPort,
                runtimeConfigService,
                playwrightDriverBundleService,
                buildPropertiesProvider,
                gitPropertiesProvider);

        assertDoesNotThrow(autoConfiguration::init);
        verify(playwrightDriverBundleService).ensureDriverReady();
    }

    @Test
    void shouldStartEnabledChannelsOnInit() {
        BotProperties properties = new BotProperties();
        BotProperties.ChannelProperties customChannelProperties = new BotProperties.ChannelProperties();
        customChannelProperties.setEnabled(true);
        properties.getChannels().put("webhook", customChannelProperties);

        ChannelPort channelPort = org.mockito.Mockito.mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn("webhook");
        when(channelCatalogPort.getAllChannels()).thenReturn(List.of(channelPort));
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(false);

        AutoConfiguration autoConfiguration = new AutoConfiguration(
                properties,
                channelCatalogPort,
                runtimeConfigService,
                playwrightDriverBundleService,
                buildPropertiesProvider,
                gitPropertiesProvider);

        autoConfiguration.init();

        verify(channelPort).start();
    }
}
