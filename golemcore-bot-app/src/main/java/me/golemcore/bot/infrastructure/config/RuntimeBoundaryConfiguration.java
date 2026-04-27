package me.golemcore.bot.infrastructure.config;

import java.util.List;
import me.golemcore.bot.domain.service.RuntimeConfigMutationService;
import me.golemcore.bot.domain.service.RuntimeConfigNormalizer;
import me.golemcore.bot.domain.service.RuntimeConfigRedactor;
import me.golemcore.bot.domain.service.RuntimeConfigSnapshotProvider;
import me.golemcore.bot.domain.service.SessionCache;
import me.golemcore.bot.domain.service.SessionCompactionBoundary;
import me.golemcore.bot.domain.service.SessionDeletionCoordinator;
import me.golemcore.bot.domain.service.SessionIdFactory;
import me.golemcore.bot.domain.service.SessionModelSettingsInheritancePolicy;
import me.golemcore.bot.domain.service.SessionRepository;
import me.golemcore.bot.domain.service.ToolArtifactPersister;
import me.golemcore.bot.domain.service.ToolArtifactService;
import me.golemcore.bot.domain.service.ToolAttachmentExtractor;
import me.golemcore.bot.domain.service.ToolResultPostProcessor;
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
