package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.golemcore.bot.domain.model.ClawHubInstallResult;
import me.golemcore.bot.domain.model.ClawHubSkillCatalog;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClawHubSkillServiceTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldLoadLatestSkillsFromClawHub() throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("clawhub")))
                .thenReturn(CompletableFuture.completedFuture(List.of("clawhub/pr-review/.clawhub-install.json")));
        when(storagePort.getText(eq("skills"), eq("clawhub/pr-review/.clawhub-install.json")))
                .thenReturn(CompletableFuture.completedFuture(JSON_MAPPER.writeValueAsString(Map.of(
                        "slug", "pr-review",
                        "version", "1.0.0",
                        "registryUrl", "https://clawhub.ai",
                        "runtimeName", "clawhub/pr-review",
                        "contentHash", "abc",
                        "installedAt", Instant.parse("2026-03-14T00:00:00Z")))));

        SkillService skillService = mock(SkillService.class);
        try (TestClawHubServer server = startServer(defaultZipBytes())) {
            ClawHubSkillService service = new ClawHubSkillService(botProperties(server.baseUrl()), storagePort,
                    skillService);

            ClawHubSkillCatalog catalog = service.getCatalog(null, 10);

            assertTrue(catalog.isAvailable());
            assertEquals(2, catalog.getItems().size());
            assertEquals("pr-review", catalog.getItems().getFirst().getSlug());
            assertTrue(catalog.getItems().getFirst().isInstalled());
        }
    }

    @Test
    void shouldInstallClawHubSkillAndRewriteRuntimeName() throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("clawhub")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.listObjects(eq("skills"), eq("clawhub/pr-review")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.putObject(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SkillService skillService = mock(SkillService.class);
        try (TestClawHubServer server = startServer(defaultZipBytes())) {
            ClawHubSkillService service = new ClawHubSkillService(botProperties(server.baseUrl()), storagePort,
                    skillService);

            ClawHubInstallResult result = service.install("pr-review", null);

            assertEquals("installed", result.getStatus());
            assertEquals("pr-review", result.getSkill().getSlug());
            assertEquals("clawhub/pr-review", result.getSkill().getRuntimeName());

            ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(storagePort, atLeastOnce()).putObject(eq("skills"), pathCaptor.capture(), contentCaptor.capture());

            Map<String, byte[]> writesByPath = writesByPath(pathCaptor, contentCaptor);

            assertTrue(writesByPath.containsKey("clawhub/pr-review/SKILL.md"));
            assertTrue(new String(writesByPath.get("clawhub/pr-review/SKILL.md"), StandardCharsets.UTF_8)
                    .contains("clawhub/pr-review"));
            verify(storagePort).putText(eq("skills"), eq("clawhub/pr-review/.clawhub-install.json"), anyString());
            verify(skillService).reload();
        }
    }

    @Test
    void shouldInstallClawHubZipWithCommonRootAndIgnoreUnsafeEntries() throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("clawhub")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.listObjects(eq("skills"), eq("clawhub/pr-review")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.putObject(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SkillService skillService = mock(SkillService.class);
        try (TestClawHubServer server = startServer(zipBytesWithCommonRootAndUnsafeEntries())) {
            ClawHubSkillService service = new ClawHubSkillService(botProperties(server.baseUrl()), storagePort,
                    skillService);

            service.install("pr-review", null);

            ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(storagePort, atLeastOnce()).putObject(eq("skills"), pathCaptor.capture(), contentCaptor.capture());

            Map<String, byte[]> writesByPath = writesByPath(pathCaptor, contentCaptor);

            assertTrue(writesByPath.containsKey("clawhub/pr-review/SKILL.md"));
            assertTrue(writesByPath.containsKey("clawhub/pr-review/assets/guide.txt"));
            assertFalse(writesByPath.keySet().stream().anyMatch(path -> path.contains("..")));
        }
    }

    @Test
    void shouldRejectClawHubArchiveWithoutSingleRootSkillFile() throws Exception {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.listObjects(eq("skills"), eq("clawhub")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.listObjects(eq("skills"), eq("clawhub/pr-review")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        SkillService skillService = mock(SkillService.class);
        try (TestClawHubServer server = startServer(zipBytesWithoutRootSkillFile())) {
            ClawHubSkillService service = new ClawHubSkillService(botProperties(server.baseUrl()), storagePort,
                    skillService);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.install("pr-review", null));
            assertEquals("ClawHub archive must contain exactly one root SKILL.md", ex.getMessage());
        }
    }

    private BotProperties botProperties(String baseUrl) {
        BotProperties properties = new BotProperties();
        properties.getSkills().setClawHubEnabled(true);
        properties.getSkills().setClawHubBaseUrl(baseUrl);
        return properties;
    }

    private TestClawHubServer startServer(byte[] zipBytes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/skills", exchange -> respond(exchange, 200, "application/json", """
                {
                  "items": [
                    {
                      "slug": "pr-review",
                      "displayName": "PR Review",
                      "summary": "Review PRs before merge.",
                      "updatedAt": 1773460511551,
                      "latestVersion": { "version": "1.0.0" }
                    },
                    {
                      "slug": "daily-review",
                      "displayName": "Daily Review",
                      "summary": "Review your day.",
                      "updatedAt": 1772062835980,
                      "latestVersion": { "version": "2.1.0" }
                    }
                  ]
                }
                """));
        server.createContext("/api/v1/skills/pr-review", exchange -> respond(exchange, 200, "application/json", """
                {
                  "skill": {
                    "slug": "pr-review",
                    "displayName": "PR Review",
                    "summary": "Review PRs before merge."
                  },
                  "latestVersion": {
                    "version": "1.0.0"
                  }
                }
                """));
        server.createContext("/api/v1/download", exchange -> respond(exchange, 200, "application/zip", zipBytes));
        server.start();
        return new TestClawHubServer(server);
    }

    private byte[] defaultZipBytes() throws IOException {
        return buildZip(Map.of(
                "SKILL.md", """
                        ---
                        name: pr-review
                        description: Review PRs before merge.
                        ---

                        Inspect the pull request and list concrete findings.
                        """,
                "README.md", "Ancillary file"));
    }

    private byte[] zipBytesWithCommonRootAndUnsafeEntries() throws IOException {
        return buildZip(Map.of(
                "pr-review/SKILL.md", """
                        ---
                        name: pr-review
                        description: Review PRs before merge.
                        ---

                        Inspect the pull request and list concrete findings.
                        """,
                "pr-review/assets/guide.txt", "Checklist",
                "../escape.txt", "blocked"));
    }

    private byte[] zipBytesWithoutRootSkillFile() throws IOException {
        return buildZip(Map.of(
                "bundle/docs.txt", "No skill file here",
                "bundle/nested/SKILL.md", """
                        ---
                        name: nested-pr-review
                        ---

                        Nested only.
                        """));
    }

    private byte[] buildZip(Map<String, String> files) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private Map<String, byte[]> writesByPath(
            ArgumentCaptor<String> pathCaptor,
            ArgumentCaptor<byte[]> contentCaptor) {
        Map<String, byte[]> writesByPath = new LinkedHashMap<>();
        for (int index = 0; index < pathCaptor.getAllValues().size(); index++) {
            writesByPath.put(pathCaptor.getAllValues().get(index), contentCaptor.getAllValues().get(index));
        }
        return writesByPath;
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

    private record TestClawHubServer(HttpServer server) implements AutoCloseable {

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}}
