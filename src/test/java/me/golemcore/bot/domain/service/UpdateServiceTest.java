package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.UpdateIntent;
import me.golemcore.bot.domain.model.UpdateState;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateServiceTest {

    private BotProperties botProperties;
    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private ApplicationContext applicationContext;
    private Clock clock;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        buildPropertiesProvider = mock(ObjectProvider.class);
        applicationContext = mock(ApplicationContext.class);
        clock = Clock.fixed(Instant.parse("2026-02-19T12:00:00Z"), ZoneOffset.UTC);

        Properties props = new Properties();
        props.setProperty("version", "0.3.0");
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(new BuildProperties(props));
    }

    @Test
    void shouldReturnDisabledStateWhenUpdateFeatureIsOff() {
        botProperties.getUpdate().setEnabled(false);
        botProperties.getUpdate().setGithubRepo("alexk-dev/golemcore-bot");

        UpdateService service = new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock);

        UpdateStatus status = service.getStatus();

        assertFalse(status.isEnabled());
        assertEquals(UpdateState.DISABLED, status.getState());
        assertNotNull(status.getCurrent());
        assertEquals("0.3.0", status.getCurrent().getVersion());
    }

    @Test
    void shouldCreateApplyIntentWhenStagedMarkerExists(@TempDir Path tempDir) throws Exception {
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setGithubRepo("alexk-dev/golemcore-bot");
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.3.1.jar"), "jar", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "bot-0.3.1.jar", StandardCharsets.UTF_8);

        UpdateService service = new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock);

        UpdateIntent intent = service.createApplyIntent();

        assertEquals("apply", intent.getOperation());
        assertEquals("0.3.1", intent.getTargetVersion());
        assertNotNull(intent.getConfirmToken());
        assertTrue(intent.getConfirmToken().length() >= 6);
        assertNotNull(intent.getExpiresAt());
    }

    @Test
    void shouldRejectApplyWhenConfirmTokenIsInvalid(@TempDir Path tempDir) throws Exception {
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setGithubRepo("alexk-dev/golemcore-bot");
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.3.1.jar"), "jar", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("staged.txt"), "bot-0.3.1.jar", StandardCharsets.UTF_8);

        UpdateService service = new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock);

        service.createApplyIntent();

        assertThrows(IllegalArgumentException.class, () -> service.apply("WRONG1"));
    }

    @Test
    void shouldCreateRollbackIntentForCachedVersion(@TempDir Path tempDir) throws Exception {
        botProperties.getUpdate().setEnabled(true);
        botProperties.getUpdate().setGithubRepo("alexk-dev/golemcore-bot");
        botProperties.getUpdate().setUpdatesPath(tempDir.toString());

        Path jarsDir = tempDir.resolve("jars");
        Files.createDirectories(jarsDir);
        Files.writeString(jarsDir.resolve("bot-0.2.9.jar"), "jar", StandardCharsets.UTF_8);

        UpdateService service = new UpdateService(
                botProperties,
                buildPropertiesProvider,
                new ObjectMapper(),
                applicationContext,
                clock);

        UpdateIntent intent = service.createRollbackIntent("0.2.9");

        assertEquals("rollback", intent.getOperation());
        assertEquals("0.2.9", intent.getTargetVersion());
        assertNotNull(intent.getConfirmToken());
    }
}
