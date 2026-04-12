package me.golemcore.bot;

import me.golemcore.bot.infrastructure.telemetry.GaTelemetryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Full Spring context smoke test — verifies that all beans wire correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

    @TempDir
    static Path tempDir;

    @MockitoBean
    GaTelemetryClient gaTelemetryClient;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("bot.storage.local.base-path", () -> tempDir.resolve("workspace").toString());
        registry.add("bot.tools.filesystem.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.tools.shell.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.plugins.enabled", () -> "false");
        registry.add("bot.plugins.auto-start", () -> "false");
        registry.add("bot.update.enabled", () -> "false");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertNotNull(context);
    }
}
