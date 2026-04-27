package me.golemcore.bot.adapter.outbound.hive;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import me.golemcore.bot.port.outbound.HiveRuntimeMetadataPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

@Component
public class HiveRuntimeMetadataAdapter implements HiveRuntimeMetadataPort {

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;

    public HiveRuntimeMetadataAdapter(
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            ObjectProvider<GitProperties> gitPropertiesProvider) {
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.gitPropertiesProvider = gitPropertiesProvider;
    }

    @Override
    public String runtimeVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        return buildProperties != null ? buildProperties.getVersion() : "dev";
    }

    @Override
    public String buildVersion() {
        GitProperties gitProperties = gitPropertiesProvider.getIfAvailable();
        if (gitProperties != null && gitProperties.getShortCommitId() != null) {
            return gitProperties.getShortCommitId();
        }
        return runtimeVersion();
    }

    @Override
    public String defaultHostLabel() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "local-host";
        }
    }

    @Override
    public long uptimeSeconds() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
    }
}
