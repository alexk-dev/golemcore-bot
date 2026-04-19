package me.golemcore.bot.infrastructure.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseWorkflowTest {

    private static final Path RELEASE_WORKFLOW = Path.of("..", ".github", "workflows", "release.yml");
    private static final String RELEASE_JOB_HEADER = "jobs:\n  release:\n";
    private static final String RELEASE_STEPS_HEADER = "\n    steps:\n";

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

    @Test
    void shouldExposeCentralTokenEnvironmentToAllReleaseSteps() throws IOException {
        String workflow = Files.readString(RELEASE_WORKFLOW);
        String releaseJob = sectionBetween(workflow, RELEASE_JOB_HEADER, RELEASE_STEPS_HEADER);

        assertTrue(releaseJob.contains("CENTRAL_TOKEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}"));
        assertTrue(releaseJob.contains("CENTRAL_TOKEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}"));
    }

    private static String sectionBetween(String content, String startMarker, String endMarker) {
        int start = content.indexOf(startMarker);
        int end = content.indexOf(endMarker, start);

        return content.substring(start, end);
    }
}
