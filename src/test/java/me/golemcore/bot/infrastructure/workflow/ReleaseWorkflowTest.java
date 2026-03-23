package me.golemcore.bot.infrastructure.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseWorkflowTest {

    private static final Path RELEASE_WORKFLOW = Path.of(".github/workflows/release.yml");

    @Test
    void shouldConfigureCentralServerCredentialsViaSetupJava() throws IOException {
        String workflow = Files.readString(RELEASE_WORKFLOW);

        assertTrue(workflow.contains("server-id: central"));
        assertTrue(workflow.contains("server-username: CENTRAL_TOKEN_USERNAME"));
        assertTrue(workflow.contains("server-password: CENTRAL_TOKEN_PASSWORD"));
    }

    @Test
    void shouldNotPassUnsupportedCentralTokenPropertiesToMaven() throws IOException {
        String workflow = Files.readString(RELEASE_WORKFLOW);

        assertFalse(workflow.contains("central.publishing.token.username"));
        assertFalse(workflow.contains("central.publishing.token.password"));
    }
}
