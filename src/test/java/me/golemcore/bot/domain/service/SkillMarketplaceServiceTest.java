package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.domain.model.SkillMarketplaceItem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillMarketplaceServiceTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldListStandaloneArtifactsFromRemoteRepository(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("repository")
                        .marketplaceBranch("main")
                        .build())
                .build());

        try (RemoteSkillsMarketplaceServer server = startRemoteRegistryServer(Map.of(
                "registry/golemcore/maintainer.yaml", maintainerYaml(),
                "registry/golemcore/code-reviewer/artifact.yaml", standaloneArtifactYaml(),
                "registry/golemcore/code-reviewer/SKILL.md", standaloneSkillMarkdown()))) {
            BotProperties properties = botProperties(server.baseUrl());
            properties.getSkills().setMarketplaceRepositoryUrl(server.repositoryUrl());
            SkillMarketplaceService service = createService(
                    properties,
                    storagePort,
                    skillService,
                    runtimeConfigService);

            SkillMarketplaceCatalog catalog = service.getCatalog();

            assertTrue(catalog.isAvailable());
            assertEquals("repository", catalog.getSourceType());
            assertEquals(server.repositoryUrl() + "/", catalog.getSourceDirectory());
            assertEquals(1, catalog.getItems().size());
            assertEquals("golemcore/code-reviewer", catalog.getItems().getFirst().getId());
            assertEquals("skill", catalog.getItems().getFirst().getArtifactType());
            assertEquals(1, catalog.getItems().getFirst().getSkillCount());
            assertEquals("smart", catalog.getItems().getFirst().getModelTier());
        }
    }

    @Test
    void shouldListPackArtifactsFromLocalDirectory(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        assertEquals("directory", catalog.getSourceType());
        assertEquals(repoRoot.toString(), catalog.getSourceDirectory());
        assertEquals(2, catalog.getItems().size());
        assertEquals("golemcore/devops-pack", catalog.getItems().stream()
                .filter(item -> "pack".equals(item.getArtifactType()))
                .findFirst()
                .orElseThrow()
                .getId());
    }

    @Test
    void shouldListPackArtifactsWithExtendedRegistryMetadataAndUnknownFields(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir, standaloneSkillMarkdown(), packArtifactYamlWithExtendedMetadata());

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        SkillMarketplaceItem pack = catalog.getItems().stream()
                .filter(item -> "golemcore/devops-pack".equals(item.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("pack", pack.getArtifactType());
        assertEquals(2, pack.getSkillCount());
    }

    @Test
    void shouldListRemotePackArtifactsWithExtendedRegistryMetadataAndUnknownFields() throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("repository")
                        .marketplaceBranch("main")
                        .build())
                .build());

        try (RemoteSkillsMarketplaceServer server = startRemoteRegistryServer(Map.of(
                "registry/obra/maintainer.yaml", obraMaintainerYaml(),
                "registry/obra/superpowers/artifact.yaml", remotePackArtifactYamlWithExtendedMetadata(),
                "registry/obra/superpowers/skills/superpowers-using-superpowers/SKILL.md",
                remotePackSkillMarkdown()))) {
            BotProperties properties = botProperties(server.baseUrl());
            properties.getSkills().setMarketplaceRepositoryUrl(server.repositoryUrl());
            SkillMarketplaceService service = createService(
                    properties,
                    storagePort,
                    skillService,
                    runtimeConfigService);

            SkillMarketplaceCatalog catalog = service.getCatalog();

            assertTrue(catalog.isAvailable());
            SkillMarketplaceItem pack = catalog.getItems().stream()
                    .filter(item -> "obra/superpowers".equals(item.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("pack", pack.getArtifactType());
            assertEquals(1, pack.getSkillCount());
            assertEquals("smart", pack.getModelTier());
        }
    }

    @Test
    void shouldUseDirectorySourceWhenConfiguredPathPointsDirectlyAtRegistryRoot(@TempDir Path tempDir)
            throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);
        Path registryRoot = repoRoot.resolve("registry");

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceRepositoryDirectory(registryRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        assertEquals("directory", catalog.getSourceType());
        assertEquals(repoRoot.toString(), catalog.getSourceDirectory());
        assertEquals(2, catalog.getItems().size());
    }

    @Test
    void shouldUseSandboxSourceWhenConfiguredPathPointsInsideWorkspace(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("sandbox")
                        .marketplaceSandboxPath("repos/golemcore-skills")
                        .build())
                .build());

        WorkspacePathService workspacePathService = mock(WorkspacePathService.class);
        when(workspacePathService.resolveSafePath("repos/golemcore-skills")).thenReturn(repoRoot);
        when(workspacePathService.toRelativePath(repoRoot)).thenReturn("repos/golemcore-skills");

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService,
                workspacePathService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        assertEquals("sandbox", catalog.getSourceType());
        assertEquals("repos/golemcore-skills", catalog.getSourceDirectory());
        assertEquals(2, catalog.getItems().size());
    }

    @Test
    void shouldInstallPackArtifactFromLocalDirectoryAndRewriteRuntimeNames(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.listObjects(eq("skills"), eq("marketplace/golemcore/devops-pack")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillInstallResult result = service.install("golemcore/devops-pack");

        assertEquals("installed", result.getStatus());
        assertNotNull(result.getSkill());
        assertEquals("golemcore/devops-pack", result.getSkill().getId());
        assertEquals("pack", result.getSkill().getArtifactType());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq("skills"), pathCaptor.capture(), contentCaptor.capture());

        Map<String, String> writesByPath = new LinkedHashMap<>();
        for (int index = 0; index < pathCaptor.getAllValues().size(); index++) {
            writesByPath.put(pathCaptor.getAllValues().get(index), contentCaptor.getAllValues().get(index));
        }

        assertTrue(writesByPath.containsKey("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"));
        assertTrue(writesByPath.containsKey("marketplace/golemcore/devops-pack/skills/incident-triage/SKILL.md"));
        assertTrue(writesByPath.containsKey("marketplace/golemcore/devops-pack/.marketplace-install.json"));

        String deployReview = writesByPath.get("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md");
        assertTrue(deployReview.contains("golemcore/devops-pack/deploy-review"));
        assertTrue(deployReview.contains("golemcore/devops-pack/incident-triage"));

        verify(skillService).reload();
    }

    @Test
    void shouldParseFrontmatterWithWhitespaceDelimitedSeparators(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir, """
                ---
                name: code-reviewer
                description: Code review skill
                model_tier: smart
                ---

                  Review code for security, correctness, and maintainability.
                """);

        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();
        SkillInstallResult result = service.install("golemcore/code-reviewer");
        String installedContent = storagePort.getText("skills", "marketplace/golemcore/code-reviewer/SKILL.md").join();

        assertEquals("smart", catalog.getItems().getFirst().getModelTier());
        assertEquals("installed", result.getStatus());
        assertNotNull(installedContent);
        assertTrue(installedContent.contains("golemcore/code-reviewer"));
        assertTrue(installedContent.contains("Review code for security, correctness, and maintainability."));
    }

    @Test
    void shouldCleanupPartialInstallWhenSkillWriteFails(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        FailingStoragePort storagePort = new FailingStoragePort(
                "marketplace/golemcore/devops-pack/skills/incident-triage/SKILL.md");
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.install("golemcore/devops-pack"));

        assertTrue(ex.getMessage().contains("Failed to install skill artifact: golemcore/devops-pack"));
        assertFalse(
                storagePort.exists("skills", "marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md").join());
        assertFalse(storagePort.exists("skills", "marketplace/golemcore/devops-pack/skills/incident-triage/SKILL.md")
                .join());
        assertFalse(storagePort.exists("skills", "marketplace/golemcore/devops-pack/.marketplace-install.json").join());
        verify(skillService, never()).reload();
    }

    @Test
    void shouldCleanupPartialInstallWhenMetadataWriteFails(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        FailingStoragePort storagePort = new FailingStoragePort(
                "marketplace/golemcore/code-reviewer/.marketplace-install.json");
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.install("golemcore/code-reviewer"));

        assertTrue(ex.getMessage().contains("Failed to install skill artifact: golemcore/code-reviewer"));
        assertFalse(storagePort.exists("skills", "marketplace/golemcore/code-reviewer/SKILL.md").join());
        assertFalse(
                storagePort.exists("skills", "marketplace/golemcore/code-reviewer/.marketplace-install.json").join());
        verify(skillService, never()).reload();
    }

    @Test
    void shouldMarkUpdateAvailableWhenInstalledMetadataVersionDiffers(@TempDir Path tempDir) throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture
                        .completedFuture(List.of("marketplace/golemcore/code-reviewer/.marketplace-install.json")));
        when(storagePort.getText(eq("skills"), eq("marketplace/golemcore/code-reviewer/.marketplace-install.json")))
                .thenReturn(CompletableFuture.completedFuture(JSON_MAPPER.writeValueAsString(Map.of(
                        "artifactRef", "golemcore/code-reviewer",
                        "version", "0.9.0",
                        "sourceType", "directory",
                        "sourceLocation", repoRoot.toString(),
                        "installedSkillNames", List.of("golemcore/code-reviewer"),
                        "installedAt", Instant.parse("2026-03-14T00:00:00Z")))));
        when(storagePort.listObjects(eq("skills"), eq("marketplace/golemcore/code-reviewer")))
                .thenReturn(CompletableFuture.completedFuture(List.of("marketplace/golemcore/code-reviewer/SKILL.md",
                        "marketplace/golemcore/code-reviewer/.marketplace-install.json")));
        when(storagePort.getText(eq("skills"), eq("marketplace/golemcore/code-reviewer/SKILL.md")))
                .thenReturn(CompletableFuture.completedFuture(standaloneSkillMarkdown()));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        assertTrue(catalog.getItems().stream()
                .filter(item -> "golemcore/code-reviewer".equals(item.getId()))
                .findFirst()
                .orElseThrow()
                .isUpdateAvailable());
    }

    @Test
    void shouldNotMarkUpdateAvailableWhenInstalledArtifactMatchesCurrentVersionAndContent(@TempDir Path tempDir)
            throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        InMemoryStoragePort storagePort = new InMemoryStoragePort();
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillInstallResult firstInstall = service.install("golemcore/code-reviewer");
        SkillMarketplaceCatalog catalog = service.getCatalog();
        SkillInstallResult secondInstall = service.install("golemcore/code-reviewer");

        assertEquals("installed", firstInstall.getStatus());
        assertFalse(catalog.getItems().stream()
                .filter(item -> "golemcore/code-reviewer".equals(item.getId()))
                .findFirst()
                .orElseThrow()
                .isUpdateAvailable());
        assertEquals("already-installed", secondInstall.getStatus());
    }

    @Test
    void shouldMarkUpdateAvailableWhenInstalledContentDriftsWithoutVersionChange(@TempDir Path tempDir)
            throws Exception {
        Path repoRoot = createLocalRegistry(tempDir);

        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace")))
                .thenReturn(CompletableFuture
                        .completedFuture(List.of("marketplace/golemcore/code-reviewer/.marketplace-install.json")));
        when(storagePort.getText(eq("skills"), eq("marketplace/golemcore/code-reviewer/.marketplace-install.json")))
                .thenReturn(CompletableFuture.completedFuture(JSON_MAPPER.writeValueAsString(Map.of(
                        "artifactRef", "golemcore/code-reviewer",
                        "version", "1.0.0",
                        "sourceType", "directory",
                        "sourceLocation", repoRoot.toString(),
                        "installedSkillNames", List.of("golemcore/code-reviewer"),
                        "installedAt", Instant.parse("2026-03-14T00:00:00Z")))));
        when(storagePort.listObjects(eq("skills"), eq("marketplace/golemcore/code-reviewer")))
                .thenReturn(CompletableFuture.completedFuture(List.of("marketplace/golemcore/code-reviewer/SKILL.md",
                        "marketplace/golemcore/code-reviewer/.marketplace-install.json")));
        when(storagePort.getText(eq("skills"), eq("marketplace/golemcore/code-reviewer/SKILL.md")))
                .thenReturn(CompletableFuture.completedFuture("""
                        ---
                        name: golemcore/code-reviewer
                        description: Code review skill
                        model_tier: smart
                        ---

                        This local copy was modified after installation.
                        """));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(RuntimeConfig.SkillsConfig.builder()
                        .marketplaceSourceType("directory")
                        .marketplaceRepositoryDirectory(repoRoot.toString())
                        .build())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertTrue(catalog.isAvailable());
        assertTrue(catalog.getItems().stream()
                .filter(item -> "golemcore/code-reviewer".equals(item.getId()))
                .findFirst()
                .orElseThrow()
                .isUpdateAvailable());
    }

    @Test
    void shouldRejectInvalidArtifactRef(@TempDir Path tempDir) {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(new RuntimeConfig.SkillsConfig())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        assertThrows(IllegalArgumentException.class, () -> service.install("../etc/passwd"));
    }

    @Test
    void shouldReturnUnavailableCatalogWhenMarketplaceDisabled(@TempDir Path tempDir) {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(new RuntimeConfig.SkillsConfig())
                .build());

        BotProperties properties = botProperties("http://127.0.0.1:1");
        properties.getSkills().setMarketplaceEnabled(false);
        SkillMarketplaceService service = createService(properties, storagePort, skillService,
                runtimeConfigService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertFalse(catalog.isAvailable());
        assertTrue(catalog.getItems().isEmpty());
    }

    @Test
    void shouldDeleteWholeInstalledArtifactForPackSkill() {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("marketplace/golemcore/devops-pack")))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        "marketplace/golemcore/devops-pack/.marketplace-install.json",
                        "marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md",
                        "marketplace/golemcore/devops-pack/skills/incident-triage/SKILL.md")));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(new RuntimeConfig.SkillsConfig())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        service.deleteManagedSkill(Skill.builder()
                .name("golemcore/devops-pack/deploy-review")
                .location(Path.of("marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md"))
                .build());

        verify(storagePort).deleteObject("skills", "marketplace/golemcore/devops-pack/.marketplace-install.json");
        verify(storagePort).deleteObject("skills", "marketplace/golemcore/devops-pack/skills/deploy-review/SKILL.md");
        verify(storagePort).deleteObject("skills", "marketplace/golemcore/devops-pack/skills/incident-triage/SKILL.md");
    }

    @Test
    void shouldDeleteSingleSkillFileWhenDeleteScopeHasNoChildren() {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("my-skill")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(new RuntimeConfig.SkillsConfig())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        service.deleteManagedSkill(Skill.builder()
                .name("my-skill")
                .location(Path.of("my-skill/SKILL.md"))
                .build());

        verify(storagePort).deleteObject("skills", "my-skill/SKILL.md");
    }

    @Test
    void shouldResolveManagedSkillStoragePathFromSkillNameWhenLocationIsMissing() {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(new RuntimeConfig.SkillsConfig())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        String path = service.resolveManagedSkillStoragePath(Skill.builder()
                .name("golemcore/code-reviewer")
                .build());

        assertEquals("golemcore/code-reviewer/SKILL.md", path);
    }

    @Test
    void shouldRejectNullSkillWhenResolvingManagedSkillStoragePath() {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .skills(new RuntimeConfig.SkillsConfig())
                .build());

        SkillMarketplaceService service = createService(
                botProperties("http://127.0.0.1:1"),
                storagePort,
                skillService,
                runtimeConfigService);

        assertThrows(NullPointerException.class, () -> service.resolveManagedSkillStoragePath(null));
    }

    private Path createLocalRegistry(Path tempDir) throws IOException {
        return createLocalRegistry(tempDir, standaloneSkillMarkdown(), packArtifactYaml());
    }

    private Path createLocalRegistry(Path tempDir, String standaloneSkillContent) throws IOException {
        return createLocalRegistry(tempDir, standaloneSkillContent, packArtifactYaml());
    }

    private Path createLocalRegistry(Path tempDir, String standaloneSkillContent, String packArtifactContent)
            throws IOException {
        Path repoRoot = tempDir.resolve("golemcore-skills");
        Files.createDirectories(repoRoot.resolve("registry/golemcore/code-reviewer"));
        Files.createDirectories(repoRoot.resolve("registry/golemcore/devops-pack/skills/deploy-review"));
        Files.createDirectories(repoRoot.resolve("registry/golemcore/devops-pack/skills/incident-triage"));

        Files.writeString(repoRoot.resolve("registry/golemcore/maintainer.yaml"), maintainerYaml());
        Files.writeString(repoRoot.resolve("registry/golemcore/code-reviewer/artifact.yaml"), standaloneArtifactYaml());
        Files.writeString(repoRoot.resolve("registry/golemcore/code-reviewer/SKILL.md"), standaloneSkillContent);
        Files.writeString(repoRoot.resolve("registry/golemcore/devops-pack/artifact.yaml"), packArtifactContent);
        Files.writeString(repoRoot.resolve("registry/golemcore/devops-pack/skills/deploy-review/SKILL.md"),
                deployReviewMarkdown());
        Files.writeString(repoRoot.resolve("registry/golemcore/devops-pack/skills/incident-triage/SKILL.md"),
                incidentTriageMarkdown());
        return repoRoot;
    }

    private BotProperties botProperties(String baseUrl) {
        BotProperties properties = new BotProperties();
        properties.getSkills().setMarketplaceEnabled(true);
        properties.getSkills().setMarketplaceRepositoryUrl(baseUrl + "/alexk-dev/golemcore-skills");
        properties.getSkills().setMarketplaceRepositoryDirectory("");
        properties.getSkills().setMarketplaceSandboxPath("");
        properties.getSkills().setMarketplaceApiBaseUrl(baseUrl + "/api");
        properties.getSkills().setMarketplaceRawBaseUrl(baseUrl + "/raw");
        properties.getSkills().setMarketplaceBranch("main");
        properties.getSkills().setMarketplaceRemoteCacheTtl(Duration.ofSeconds(30));
        return properties;
    }

    private SkillMarketplaceService createService(
            BotProperties properties,
            StoragePort storagePort,
            SkillService skillService,
            RuntimeConfigService runtimeConfigService) {
        return createService(properties, storagePort, skillService, runtimeConfigService,
                mock(WorkspacePathService.class));
    }

    private SkillMarketplaceService createService(
            BotProperties properties,
            StoragePort storagePort,
            SkillService skillService,
            RuntimeConfigService runtimeConfigService,
            WorkspacePathService workspacePathService) {
        return new SkillMarketplaceService(properties, storagePort, skillService, runtimeConfigService,
                workspacePathService);
    }

    private static String maintainerYaml() {
        return """
                schema: v1
                id: golemcore
                display_name: Golemcore
                """;
    }

    private static String standaloneArtifactYaml() {
        return """
                schema: v1
                type: skill
                maintainer: golemcore
                id: code-reviewer
                version: 1.0.0
                title: Code Reviewer
                description: Review code for correctness, risks, and maintainability.
                """;
    }

    private static String packArtifactYaml() {
        return """
                schema: v1
                type: pack
                maintainer: golemcore
                id: devops-pack
                version: 1.2.0
                title: DevOps Pack
                description: Delivery and incident response skills.
                skills:
                  - id: deploy-review
                    path: skills/deploy-review/SKILL.md
                  - id: incident-triage
                    path: skills/incident-triage/SKILL.md
                """;
    }

    private static String packArtifactYamlWithExtendedMetadata() {
        return """
                schema: v1
                type: pack
                maintainer: golemcore
                id: devops-pack
                version: 1.2.0
                title: DevOps Pack
                description: Delivery and incident response skills.
                license: MIT
                source:
                  repository: https://github.com/obra/superpowers
                  author: Jesse Vincent
                  author_url: https://github.com/obra
                  license: MIT
                  homepage: https://example.com/ignored
                attribution: Adapted for Golemcore Bot runtime.
                tags:
                  - workflow
                  - review
                extra_field: ignored-by-parser
                skills:
                  - id: deploy-review
                    path: skills/deploy-review/SKILL.md
                  - id: incident-triage
                    path: skills/incident-triage/SKILL.md
                """;
    }

    private static String obraMaintainerYaml() {
        return """
                schema: v1
                id: obra
                display_name: Obra
                github: obra
                website: https://github.com/obra
                """;
    }

    private static String remotePackArtifactYamlWithExtendedMetadata() {
        return """
                schema: v1
                type: pack
                maintainer: obra
                id: superpowers
                version: 1.0.1
                title: Superpowers
                description: Adapted workflow pack inspired by obra/superpowers for Golemcore Bot.
                license: MIT
                source:
                  repository: https://github.com/obra/superpowers
                  author: Jesse Vincent
                  author_url: https://github.com/obra
                  license: MIT
                  notes: ignored
                attribution: Adapted for Golemcore Bot runtime and golemcore-skills packaging.
                tags:
                  - workflow
                  - engineering
                unknown_top_level: ignored
                skills:
                  - id: superpowers-using-superpowers
                    path: skills/superpowers-using-superpowers/SKILL.md
                """;
    }

    private static String remotePackSkillMarkdown() {
        return """
                ---
                name: superpowers-using-superpowers
                description: Coordinate the rest of the superpowers workflow.
                model_tier: smart
                ---

                Coordinate planning and execution across the rest of the pack.
                """;
    }

    private static String standaloneSkillMarkdown() {
        return """
                ---
                name: code-reviewer
                description: Code review skill
                model_tier: smart
                ---

                Review code for security, correctness, and maintainability.
                """;
    }

    private static String deployReviewMarkdown() {
        return """
                ---
                name: deploy-review
                description: Review deploy plans before rollout.
                model_tier: balanced
                next_skill: incident-triage
                ---

                Review deployment checklists and verify risk areas.
                """;
    }

    private static String incidentTriageMarkdown() {
        return """
                ---
                name: incident-triage
                description: Triage incidents and identify the next operator action.
                model_tier: smart
                ---

                Summarize signals and recommend the next operational step.
                """;
    }

    private RemoteSkillsMarketplaceServer startRemoteRegistryServer(Map<String, String> files) throws IOException {
        String treeJson = buildTreeJson(files.keySet().stream().sorted().toList());

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/repos/alexk-dev/golemcore-skills/git/trees/main",
                exchange -> respond(exchange, 200, "application/json", treeJson));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            server.createContext("/raw/alexk-dev/golemcore-skills/main/" + entry.getKey(),
                    exchange -> respond(exchange, 200, "text/plain; charset=utf-8", entry.getValue()));
        }
        server.start();

        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        return new RemoteSkillsMarketplaceServer(server, baseUrl);
    }

    private String buildTreeJson(List<String> files) {
        StringBuilder treeBuilder = new StringBuilder();
        treeBuilder.append("{\"tree\":[");
        for (int index = 0; index < files.size(); index++) {
            if (index > 0) {
                treeBuilder.append(',');
            }
            treeBuilder.append("{\"path\":\"")
                    .append(files.get(index))
                    .append("\",\"type\":\"blob\"}");
        }
        treeBuilder.append("]}");
        return treeBuilder.toString();
    }

    private void respond(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        respond(exchange, statusCode, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
        try (HttpExchange closableExchange = exchange;
                OutputStream outputStream = closableExchange.getResponseBody()) {
            closableExchange.getResponseHeaders().set("Content-Type", contentType);
            closableExchange.sendResponseHeaders(statusCode, body.length);
            outputStream.write(body);
        }
    }

    private record RemoteSkillsMarketplaceServer(HttpServer server, String baseUrl) implements AutoCloseable {

    private String repositoryUrl() {
        return baseUrl + "/alexk-dev/golemcore-skills";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

private static class InMemoryStoragePort implements StoragePort {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    @Override
    public CompletableFuture<Void> putObject(String directory, String path, byte[] content) {
        objects.put(key(directory, path), content);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> putText(String directory, String path, String content) {
        return putObject(directory, path,
                content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompletableFuture<byte[]> getObject(String directory, String path) {
        return CompletableFuture.completedFuture(objects.get(key(directory, path)));
    }

    @Override
    public CompletableFuture<String> getText(String directory, String path) {
        byte[] value = objects.get(key(directory, path));
        return CompletableFuture.completedFuture(
                value == null ? null : new String(value, StandardCharsets.UTF_8));
    }

    @Override
    public CompletableFuture<Boolean> exists(String directory, String path) {
        return CompletableFuture.completedFuture(objects.containsKey(key(directory, path)));
    }

    @Override
    public CompletableFuture<Void> deleteObject(String directory, String path) {
        objects.remove(key(directory, path));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<String>> listObjects(String directory, String prefix) {
        String normalizedPrefix = key(directory, prefix);
        List<String> matches = new ArrayList<>();
        for (String key : objects.keySet()) {
            if (!key.startsWith(directory + "/")) {
                continue;
            }
            if (!key.startsWith(normalizedPrefix)) {
                continue;
            }
            matches.add(key.substring(directory.length() + 1));
        }
        matches.sort(String::compareTo);
        return CompletableFuture.completedFuture(matches);
    }

    @Override
    public CompletableFuture<Void> appendText(String directory, String path, String content) {
        String current = getText(directory, path).join();
        return putText(directory, path, (current == null ? "" : current) + content);
    }

    @Override
    public CompletableFuture<Void> putTextAtomic(String directory, String path, String content, boolean backup) {
        return putText(directory, path, content);
    }

    @Override
    public CompletableFuture<Void> ensureDirectory(String directory) {
        return CompletableFuture.completedFuture(null);
    }

    private String key(String directory, String path) {
        return directory + "/" + path;
    }
}

private static final class FailingStoragePort extends InMemoryStoragePort {

    private final String failingPath;

    private FailingStoragePort(String failingPath) {
        this.failingPath = failingPath;
    }

    @Override
    public CompletableFuture<Void> putText(String directory, String path, String content) {
        if ("skills".equals(directory) && failingPath.equals(path)) {
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }
        return super.putText(directory, path, content);
    }
}}
