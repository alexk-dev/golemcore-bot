package me.golemcore.bot.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import me.golemcore.bot.infrastructure.telemetry.GaTelemetryClient;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(GolemCoreBotIntegrationTestConfiguration.class)
abstract class GolemCoreBotIntegrationTestBase {

    protected static final String ADMIN_PASSWORD = "integration-admin-password";

    private static final String JWT_SECRET = "integration-test-jwt-secret-key-that-is-long-enough-for-hmac-sha256";

    @TempDir
    static Path tempDir;

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockitoBean
    GaTelemetryClient gaTelemetryClient;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("bot.storage.local.base-path", () -> tempDir.resolve("workspace").toString());
        registry.add("bot.tools.filesystem.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.tools.shell.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.dashboard.enabled", () -> "true");
        registry.add("bot.dashboard.admin-password", () -> ADMIN_PASSWORD);
        registry.add("bot.dashboard.jwt-secret", () -> JWT_SECRET);
        registry.add("bot.llm.provider", () -> "none");
        registry.add("bot.plugins.enabled", () -> "false");
        registry.add("bot.plugins.auto-start", () -> "false");
        registry.add("bot.plugins.auto-reload", () -> "false");
        registry.add("bot.update.enabled", () -> "false");
        registry.add("bot.skills.marketplace-enabled", () -> "false");
        registry.add("bot.self-evolving.bootstrap.enabled", () -> "false");
        registry.add("bot.self-evolving.bootstrap.tactics.enabled", () -> "false");
        registry.add("bot.self-evolving.bootstrap.tactics.search.mode", () -> "bm25");
        registry.add("bot.self-evolving.bootstrap.tactics.search.embeddings.local.auto-install", () -> "false");
        registry.add("bot.self-evolving.bootstrap.tactics.search.embeddings.local.pull-on-start", () -> "false");
    }

    protected WebTestClient webTestClient() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    protected String loginAndExtractAccessToken() {
        String body = webTestClient().post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"password\":\"" + ADMIN_PASSWORD + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return parseJson(body).path("accessToken").asText();
    }

    protected WebTestClient.RequestHeadersSpec<?> authenticatedGet(String uri, String accessToken) {
        return webTestClient().get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    protected WebTestClient.RequestHeadersSpec<?> authenticatedPut(String uri, String accessToken, String body) {
        return webTestClient().put()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    protected JsonNode getRuntimeConfig(String accessToken) {
        String body = authenticatedGet("/api/settings/runtime", accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return parseJson(body);
    }

    protected JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse test response JSON", exception);
        }
    }

    protected JsonNode readPersistedPreferenceSection(String fileName) throws Exception {
        Path sectionPath = tempDir.resolve("workspace").resolve("preferences").resolve(fileName);
        return objectMapper.readTree(Files.readString(sectionPath));
    }
}