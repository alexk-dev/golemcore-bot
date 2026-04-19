package me.golemcore.bot.application.skills;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.model.SkillMarketplaceItem;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.SkillMarketplaceArtifactPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceInstallPort;
import me.golemcore.bot.port.outbound.SkillSettingsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillMarketplaceServiceTest {

    private SkillSettingsPort skillSettingsPort;
    private RuntimeConfigService runtimeConfigService;
    private SkillMarketplaceCatalogPort catalogPort;
    private SkillMarketplaceArtifactPort artifactPort;
    private SkillMarketplaceInstallPort installPort;
    private SkillMarketplaceService service;

    @BeforeEach
    void setUp() {
        skillSettingsPort = mock(SkillSettingsPort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        catalogPort = mock(SkillMarketplaceCatalogPort.class);
        artifactPort = mock(SkillMarketplaceArtifactPort.class);
        installPort = mock(SkillMarketplaceInstallPort.class);
        service = new SkillMarketplaceService(
                skillSettingsPort,
                runtimeConfigService,
                catalogPort,
                artifactPort,
                installPort);

        when(skillSettingsPort.skills()).thenReturn(new SkillSettingsPort.SkillSettings(
                "skills",
                marketplaceSettings(true)));

        RuntimeConfig config = new RuntimeConfig();
        RuntimeConfig.SkillsConfig skillsConfig = new RuntimeConfig.SkillsConfig();
        skillsConfig.setMarketplaceSourceType("remote");
        skillsConfig.setMarketplaceRepositoryDirectory(" ");
        skillsConfig.setMarketplaceSandboxPath("/sandbox");
        skillsConfig.setMarketplaceRepositoryUrl("https://example.test/skills");
        skillsConfig.setMarketplaceBranch("main");
        config.setSkills(skillsConfig);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(config);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void shouldInstallArtifactUsingCatalogSnapshotAndFallbackItem() {
        SkillMarketplaceCatalogPort.MarketplaceArtifactData artifact = artifact();
        SkillMarketplaceCatalogPort.MarketplaceSourceRef source = source();
        SkillMarketplaceArtifactPort.InstalledArtifactContent content = installedContent("golemcore/devops-pack",
                "hash-1");
        SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData catalogData = new SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData(
                null, Map.of(), Map.of("golemcore/devops-pack", artifact), source);

        when(catalogPort.loadCatalog(any(), anyMap(), any(), any(), any(), any()))
                .thenReturn(catalogData);
        when(artifactPort.loadArtifactContent(source, artifact, "golemcore/devops-pack"))
                .thenReturn(content);

        SkillInstallResult result = service.install(" GolemCore/DevOps-Pack ");

        assertEquals("installed", result.getStatus());
        assertTrue(result.getMessage().contains("installed"));
        assertEquals("golemcore/devops-pack", result.getSkill().getId());
        assertEquals("devops-pack", result.getSkill().getArtifactId());
        assertEquals(List.of("golemcore/devops-pack/deploy-review"), result.getSkill().getSkillRefs());
        assertEquals("coding", result.getSkill().getModelTier());

        ArgumentCaptor<SkillMarketplaceInstallPort.InstalledArtifactInstallRequest> requestCaptor = ArgumentCaptor
                .forClass(SkillMarketplaceInstallPort.InstalledArtifactInstallRequest.class);
        verify(installPort).install(requestCaptor.capture());
        assertEquals("golemcore/devops-pack", requestCaptor.getValue().artifactRef());
        assertEquals("hash-1", requestCaptor.getValue().artifactHash());

        ArgumentCaptor<Map> runtimeSettingsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(catalogPort, times(2)).loadCatalog(
                any(),
                runtimeSettingsCaptor.capture(),
                eq(" "),
                eq("/sandbox"),
                eq("https://example.test/skills"),
                eq("main"));
        assertEquals("remote", runtimeSettingsCaptor.getAllValues().getFirst().get("marketplaceSourceType"));
        assertEquals("/sandbox", runtimeSettingsCaptor.getAllValues().getFirst().get("marketplaceSandboxPath"));
        assertEquals("https://example.test/skills",
                runtimeSettingsCaptor.getAllValues().getFirst().get("marketplaceRepositoryUrl"));
    }

    @Test
    void shouldReportAlreadyInstalledAndUpdatedStatusesFromInstalledMetadata() {
        SkillMarketplaceCatalogPort.MarketplaceArtifactData artifact = artifact();
        SkillMarketplaceCatalogPort.MarketplaceSourceRef source = source();
        SkillMarketplaceArtifactPort.InstalledArtifactContent content = installedContent("golemcore/devops-pack",
                "hash-1");
        SkillMarketplaceCatalog refreshedCatalog = SkillMarketplaceCatalog.builder()
                .items(List.of(SkillMarketplaceItem.builder()
                        .id("golemcore/devops-pack")
                        .name("Catalog name")
                        .build()))
                .build();
        SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData sameInstalled = catalogData(
                artifact,
                source,
                new SkillMarketplaceCatalogPort.InstalledMarketplaceArtifact(
                        "golemcore/devops-pack",
                        "1.0.0",
                        "hash-1",
                        "content-hash",
                        "content-hash"),
                null);
        SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData driftedInstalled = catalogData(
                artifact,
                source,
                new SkillMarketplaceCatalogPort.InstalledMarketplaceArtifact(
                        "golemcore/devops-pack",
                        "0.9.0",
                        "old-hash",
                        "expected",
                        "current"),
                null);
        SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData refreshed = new SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData(
                refreshedCatalog, Map.of(), Map.of(), source);

        when(catalogPort.loadCatalog(any(), anyMap(), any(), any(), any(), any()))
                .thenReturn(sameInstalled, refreshed, driftedInstalled, refreshed);
        when(artifactPort.loadArtifactContent(source, artifact, "golemcore/devops-pack"))
                .thenReturn(content);

        SkillInstallResult alreadyInstalled = service.install("golemcore/devops-pack");
        SkillInstallResult updated = service.install("golemcore/devops-pack");

        assertEquals("already-installed", alreadyInstalled.getStatus());
        assertEquals("Catalog name", alreadyInstalled.getSkill().getName());
        assertEquals("updated", updated.getStatus());
        assertTrue(updated.getMessage().contains("updated"));
    }

    @Test
    void shouldRejectInvalidOrDisabledMarketplaceRequestsAndDelegateManagedOperations() {
        assertThrows(IllegalArgumentException.class, () -> service.install(" "));
        assertThrows(IllegalArgumentException.class, () -> service.install("invalid"));
        assertThrows(IllegalArgumentException.class, () -> service.install("-bad/devops"));

        when(skillSettingsPort.skills()).thenReturn(new SkillSettingsPort.SkillSettings(
                "skills",
                marketplaceSettings(false)));
        assertThrows(IllegalStateException.class, () -> service.install("golemcore/devops-pack"));

        Skill skill = Skill.builder().name("managed").build();
        when(installPort.resolveManagedSkillStoragePath(skill)).thenReturn("marketplace/golemcore/devops-pack");
        when(installPort.resolveMarketplaceInstallBase(Path.of("marketplace/golemcore/devops-pack")))
                .thenReturn(Optional.of("marketplace/golemcore"));

        service.deleteManagedSkill(skill);

        assertEquals("marketplace/golemcore/devops-pack", service.resolveManagedSkillStoragePath(skill));
        assertEquals(Optional.of("marketplace/golemcore"),
                service.resolveMarketplaceInstallBase(Path.of("marketplace/golemcore/devops-pack")));
        verify(installPort).deleteManagedSkill(skill);
    }

    private SkillSettingsPort.MarketplaceSettings marketplaceSettings(boolean enabled) {
        return new SkillSettingsPort.MarketplaceSettings(
                enabled,
                "/repo",
                "/sandbox-setting",
                "https://settings.example/skills",
                "settings-branch",
                "https://api.example",
                "https://raw.example",
                Duration.ofMinutes(5));
    }

    private SkillMarketplaceCatalogPort.MarketplaceArtifactData artifact() {
        return new SkillMarketplaceCatalogPort.MarketplaceArtifactData(
                "golemcore/devops-pack",
                "1.0.0",
                "hash-1",
                "remote",
                "https://example.test/skills");
    }

    private SkillMarketplaceCatalogPort.MarketplaceSourceRef source() {
        return new SkillMarketplaceCatalogPort.MarketplaceSourceRef(
                "remote",
                "https://example.test/skills",
                "https://example.test/skills",
                "main");
    }

    private SkillMarketplaceArtifactPort.InstalledArtifactContent installedContent(String artifactRef, String hash) {
        return new SkillMarketplaceArtifactPort.InstalledArtifactContent(
                artifactRef,
                "pack",
                "1.0.0",
                hash,
                List.of(new SkillMarketplaceArtifactPort.InstalledSkillDocument(
                        "deploy-review",
                        "golemcore/devops-pack/deploy-review",
                        "golemcore/devops-pack/deploy-review/SKILL.md",
                        "content",
                        "Review deployment plans",
                        "coding")),
                "remote",
                "https://example.test/skills");
    }

    private SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData catalogData(
            SkillMarketplaceCatalogPort.MarketplaceArtifactData artifact,
            SkillMarketplaceCatalogPort.MarketplaceSourceRef source,
            SkillMarketplaceCatalogPort.InstalledMarketplaceArtifact installed,
            SkillMarketplaceCatalog catalog) {
        return new SkillMarketplaceCatalogPort.SkillMarketplaceCatalogData(
                catalog,
                Map.of("golemcore/devops-pack", installed),
                Map.of("golemcore/devops-pack", artifact),
                source);
    }
}
