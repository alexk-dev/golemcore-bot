package me.golemcore.bot.domain.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillInstallResult;
import me.golemcore.bot.domain.model.SkillMarketplaceCatalog;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillMarketplaceServiceTest {

    @Test
    void shouldListMarketplaceItemsFromRemoteRepository(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of());

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(skillMarkdown())) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            SkillMarketplaceCatalog catalog = service.getCatalog();

            assertTrue(catalog.isAvailable());
            assertEquals(server.repositoryUrl(), catalog.getSourceDirectory());
            assertEquals(1, catalog.getItems().size());
            assertEquals("code-reviewer", catalog.getItems().getFirst().getId());
            assertEquals("Code review skill", catalog.getItems().getFirst().getDescription());
            assertEquals("smart", catalog.getItems().getFirst().getModelTier());
        }
    }

    @Test
    void shouldMarkUpdateAvailableWhenInstalledSkillDiffers(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of(Skill.builder()
                .name("code-reviewer")
                .description("Old description")
                .modelTier("balanced")
                .build()));

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(skillMarkdown())) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            SkillMarketplaceCatalog catalog = service.getCatalog();

            assertTrue(catalog.isAvailable());
            assertEquals(1, catalog.getItems().size());
            assertTrue(catalog.getItems().getFirst().isInstalled());
            assertTrue(catalog.getItems().getFirst().isUpdateAvailable());
        }
    }

    @Test
    void shouldInstallSkillFromMarketplace(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.putText(eq("skills"), eq("code-reviewer/SKILL.md"), eq(skillMarkdown())))
                .thenReturn(CompletableFuture.completedFuture(null));

        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of());
        when(skillService.findByName("code-reviewer"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(Skill.builder()
                        .name("code-reviewer")
                        .description("Code review skill")
                        .modelTier("smart")
                        .build()));

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(skillMarkdown())) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            SkillInstallResult result = service.install("code-reviewer");

            assertEquals("installed", result.getStatus());
            assertTrue(result.getMessage().contains("installed"));
            assertNotNull(result.getSkill());
            assertEquals("code-reviewer", result.getSkill().getId());
            assertTrue(result.getSkill().isInstalled());
            verify(storagePort).putText("skills", "code-reviewer/SKILL.md", skillMarkdown());
            verify(skillService).reload();
        }
    }

    @Test
    void shouldReturnAlreadyInstalledWhenSkillContentMatches(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.putText(eq("skills"), eq("code-reviewer/SKILL.md"), eq(skillMarkdown())))
                .thenReturn(CompletableFuture.completedFuture(null));

        Skill existing = Skill.builder()
                .name("code-reviewer")
                .description("Code review skill")
                .modelTier("smart")
                .build();

        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of(existing));
        when(skillService.findByName("code-reviewer"))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing));

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(skillMarkdown())) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            SkillInstallResult result = service.install("code-reviewer");

            assertEquals("already-installed", result.getStatus());
            assertTrue(result.getMessage().contains("already"));
        }
    }

    @Test
    void shouldReturnUpdatedWhenMarketplaceDescriptionWasRemoved(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        String noDescriptionMarkdown = """
                ---
                model_tier: smart
                ---

                Review code for security, correctness, and maintainability.
                """;
        when(storagePort.putText(eq("skills"), eq("code-reviewer/SKILL.md"), eq(noDescriptionMarkdown)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Skill existing = Skill.builder()
                .name("code-reviewer")
                .description("Code review skill")
                .modelTier("smart")
                .build();

        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of(existing));
        when(skillService.findByName("code-reviewer"))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing));

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(noDescriptionMarkdown)) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            SkillInstallResult result = service.install("code-reviewer");

            assertEquals("updated", result.getStatus());
            assertTrue(result.getMessage().contains("updated"));
        }
    }

    @Test
    void shouldRejectUnknownSkill(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of());

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(skillMarkdown())) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            assertThrows(IllegalArgumentException.class, () -> service.install("unknown-skill"));
        }
    }

    @Test
    void shouldReturnUnavailableCatalogWhenMarketplaceDisabled(@TempDir Path tempDir) {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);

        BotProperties properties = botProperties("http://127.0.0.1:1");
        properties.getSkills().setMarketplaceEnabled(false);
        SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

        SkillMarketplaceCatalog catalog = service.getCatalog();

        assertFalse(catalog.isAvailable());
        assertTrue(catalog.getItems().isEmpty());
    }

    @Test
    void shouldRejectInvalidSkillId(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(skillMarkdown())) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            assertThrows(IllegalArgumentException.class, () -> service.install("../etc/passwd"));
        }
    }

    @Test
    void shouldIgnoreNestedSkillPath(@TempDir Path tempDir) throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        SkillService skillService = mock(SkillService.class);
        when(skillService.getAllSkills()).thenReturn(List.of());

        try (RemoteSkillsMarketplaceServer server = startRemoteSkillsMarketplaceServer(
                skillMarkdown(),
                "nested/code-reviewer/SKILL.md")) {
            BotProperties properties = botProperties(server.baseUrl());
            SkillMarketplaceService service = new SkillMarketplaceService(properties, storagePort, skillService);

            SkillMarketplaceCatalog catalog = service.getCatalog();

            assertTrue(catalog.isAvailable());
            assertTrue(catalog.getItems().isEmpty());
        }
    }

    private BotProperties botProperties(String baseUrl) {
        BotProperties properties = new BotProperties();
        properties.getSkills().setMarketplaceEnabled(true);
        properties.getSkills().setMarketplaceRepositoryUrl(baseUrl + "/alexk-dev/golemcore-skills");
        properties.getSkills().setMarketplaceApiBaseUrl(baseUrl + "/api");
        properties.getSkills().setMarketplaceRawBaseUrl(baseUrl + "/raw");
        properties.getSkills().setMarketplaceBranch("main");
        properties.getSkills().setMarketplaceRemoteCacheTtl(Duration.ofSeconds(30));
        return properties;
    }

    private RemoteSkillsMarketplaceServer startRemoteSkillsMarketplaceServer(String markdown) throws IOException {
        return startRemoteSkillsMarketplaceServer(markdown, "code-reviewer/SKILL.md");
    }

    private RemoteSkillsMarketplaceServer startRemoteSkillsMarketplaceServer(String markdown, String treePath)
            throws IOException {
        String treeJson = """
                {
                  "tree": [
                    {
                      "path": "%s",
                      "type": "blob"
                    }
                  ]
                }
                """.formatted(treePath);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/repos/alexk-dev/golemcore-skills/git/trees/main",
                exchange -> respond(exchange, 200, "application/json", treeJson));
        server.createContext("/raw/alexk-dev/golemcore-skills/main/" + treePath,
                exchange -> respond(exchange, 200, "text/plain; charset=utf-8", markdown));
        server.start();

        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        return new RemoteSkillsMarketplaceServer(server, baseUrl);
    }

    private static String skillMarkdown() {
        return """
                ---
                description: "Code review skill"
                model_tier: smart
                ---

                Review code for security, correctness, and maintainability.
                """;
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
            return baseUrl + "/alexk-dev/golemcore-skills/";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
