package me.golemcore.bot.domain.system;

import me.golemcore.bot.adapter.outbound.channel.ChannelRegistryRuntimeAdapter;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResponseRoutingSystemWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ChannelRegistry.class, () -> {
                ChannelPort channel = mock(ChannelPort.class);
                when(channel.getChannelType()).thenReturn("telegram");
                return new ChannelRegistry(List.of(channel));
            })
            .withBean(ChannelRegistryRuntimeAdapter.class)
            .withBean(UserPreferencesService.class, () -> mock(UserPreferencesService.class))
            .withBean(VoiceResponseHandler.class, () -> mock(VoiceResponseHandler.class))
            .withBean(RuntimeConfigService.class, () -> mock(RuntimeConfigService.class))
            .withBean(TraceService.class,
                    () -> new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService()))
            .withBean(ResponseRoutingSystem.class);

    @Test
    void shouldCreateResponseRoutingSystemBeanUsingChannelRuntimePort() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ResponseRoutingSystem.class));
    }
}
