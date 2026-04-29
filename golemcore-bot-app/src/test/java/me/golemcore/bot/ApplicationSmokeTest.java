package me.golemcore.bot;

import me.golemcore.bot.infrastructure.telemetry.GaTelemetryClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Full Spring context smoke test — verifies that all beans wire correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@MockitoBean(types = GaTelemetryClient.class)
class ApplicationSmokeTest {

    @TempDir
    static Path tempDir;

    private final ApplicationContext context;

    ApplicationSmokeTest(ApplicationContext context) {
        this.context = context;
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("bot.storage.local.base-path", () -> tempDir.resolve("workspace").toString());
        registry.add("bot.tools.filesystem.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.tools.shell.workspace", () -> tempDir.resolve("sandbox").toString());
        registry.add("bot.plugins.enabled", () -> "false");
        registry.add("bot.plugins.auto-start", () -> "false");
        registry.add("bot.update.enabled", () -> "false");
    }

    @Test
    void contextLoads() {
        assertNotNull(context);
    }

    @Test
    void pluginRuntimeApiBeansAreExposed() {
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.ActiveSessionPointerService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.AutoModeService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.ModelSelectionService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.PlanExecutionService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.PlanService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.PluginConfigurationService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.RagIngestionService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.RagProviderDiscoveryService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.RuntimeConfigService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.UserPreferencesService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.i18n.MessageService.class));
        assertNotNull(context.getBean(me.golemcore.plugin.api.runtime.security.AllowlistValidator.class));
    }
}
