package me.golemcore.bot.infrastructure.config;

import java.util.List;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigMutationService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigNormalizer;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigRedactor;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSnapshotProvider;
import me.golemcore.bot.domain.sessions.SessionCache;
import me.golemcore.bot.domain.sessions.SessionCompactionBoundary;
import me.golemcore.bot.domain.sessions.SessionDeletionCoordinator;
import me.golemcore.bot.domain.sessions.SessionIdFactory;
import me.golemcore.bot.domain.sessions.SessionModelSettingsInheritancePolicy;
import me.golemcore.bot.domain.sessions.SessionRepository;
import me.golemcore.bot.domain.tools.artifacts.ToolArtifactPersister;
import me.golemcore.bot.domain.tools.artifacts.ToolArtifactService;
import me.golemcore.bot.domain.tools.execution.ToolAttachmentExtractor;
import me.golemcore.bot.domain.tools.execution.ToolResultPostProcessor;
import me.golemcore.bot.port.outbound.RuntimeConfigPersistencePort;
import me.golemcore.bot.port.outbound.SessionGoalCleanupPort;
import me.golemcore.bot.port.outbound.SessionRecordCodecPort;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeBoundaryConfiguration {

    @Bean
    RuntimeConfigSnapshotProvider runtimeConfigSnapshotProvider() {
        return new RuntimeConfigSnapshotProvider();
    }

    @Bean
    RuntimeConfigMutationService runtimeConfigMutationService(
            RuntimeConfigPersistencePort runtimeConfigPersistencePort,
            RuntimeConfigSnapshotProvider snapshotProvider) {
        return new RuntimeConfigMutationService(runtimeConfigPersistencePort, snapshotProvider);
    }

    @Bean
    RuntimeConfigRedactor runtimeConfigRedactor() {
        return new RuntimeConfigRedactor();
    }

    @Bean
    RuntimeConfigNormalizer runtimeConfigNormalizer() {
        return new RuntimeConfigNormalizer();
    }

    @Bean
    SessionIdFactory sessionIdFactory() {
        return new SessionIdFactory();
    }

    @Bean
    SessionCache sessionCache() {
        return new SessionCache();
    }

    @Bean
    SessionRepository sessionRepository(
            StoragePort storagePort,
            SessionRecordCodecPort sessionRecordCodecPort,
            SessionIdFactory sessionIdFactory) {
        return new SessionRepository(storagePort, sessionRecordCodecPort, sessionIdFactory);
    }

    @Bean
    SessionCompactionBoundary sessionCompactionBoundary() {
        return new SessionCompactionBoundary();
    }

    @Bean
    SessionModelSettingsInheritancePolicy sessionModelSettingsInheritancePolicy(
            SessionCache sessionCache,
            SessionRepository sessionRepository) {
        return new SessionModelSettingsInheritancePolicy(sessionCache, sessionRepository);
    }

    @Bean
    SessionDeletionCoordinator sessionDeletionCoordinator(
            SessionCache sessionCache,
            SessionRepository sessionRepository,
            List<SessionGoalCleanupPort> sessionGoalCleanupPorts) {
        return new SessionDeletionCoordinator(sessionCache, sessionRepository, sessionGoalCleanupPorts);
    }

    @Bean
    ToolAttachmentExtractor toolAttachmentExtractor() {
        return new ToolAttachmentExtractor();
    }

    @Bean
    ToolArtifactPersister toolArtifactPersister(ToolArtifactService toolArtifactService) {
        return new ToolArtifactPersister(toolArtifactService);
    }

    @Bean
    ToolResultPostProcessor toolResultPostProcessor(ToolRuntimeSettingsPort settingsPort) {
        return new ToolResultPostProcessor(settingsPort);
    }
}
