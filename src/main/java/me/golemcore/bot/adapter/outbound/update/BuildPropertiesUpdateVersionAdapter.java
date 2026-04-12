package me.golemcore.bot.adapter.outbound.update;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BuildPropertiesUpdateVersionAdapter implements UpdateVersionPort {

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    @Override
    public String currentVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        if (buildProperties == null || buildProperties.getVersion() == null || buildProperties.getVersion().isBlank()) {
            return "dev";
        }
        return buildProperties.getVersion();
    }
}
