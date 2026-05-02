package me.golemcore.bot.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import me.golemcore.bot.domain.cli.CliEvent;
import me.golemcore.bot.domain.cli.CliEventSeverity;
import me.golemcore.bot.domain.cli.CliEventType;
import org.junit.jupiter.api.Test;

class JsonPresenterSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRedactSensitiveEventPayloadBeforeJsonOutput() throws Exception {
        JsonPresenter presenter = new JsonPresenter(objectMapper, RedactionPolicy.defaultPolicy());
        CliEvent event = new CliEvent(
                "cli-event/v1",
                "evt_1",
                1,
                CliEventType.TOOL_OUTPUT_DELTA,
                "run_1",
                "ses_1",
                "project_1",
                "trace_1",
                null,
                null,
                Instant.parse("2026-04-28T10:15:30Z"),
                CliEventSeverity.INFO,
                Map.of(
                        "apiKey", "sk-live-value",
                        "nested", Map.of("password", "open-sesame"),
                        "safe", "visible"));
        StringWriter out = new StringWriter();

        presenter.render(event, new PrintWriter(out, true));

        JsonNode node = objectMapper.readTree(out.toString());
        assertEquals("[REDACTED]", node.at("/payload/apiKey").asText());
        assertEquals("[REDACTED]", node.at("/payload/nested/password").asText());
        assertEquals("visible", node.at("/payload/safe").asText());
    }
}
