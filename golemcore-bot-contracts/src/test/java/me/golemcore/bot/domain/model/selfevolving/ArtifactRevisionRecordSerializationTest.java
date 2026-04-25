package me.golemcore.bot.domain.model.selfevolving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactRevisionRecordSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldSerializeArtifactRevisionRecordWithStreamAndOriginIds() throws Exception {
        ArtifactRevisionRecord record = ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("tool_policy:usage")
                .artifactType("tool_policy")
                .artifactSubtype("tool_policy:usage")
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .rawContent("selfevolving:fix:tool_policy")
                .sourceRunIds(List.of("run-1"))
                .createdAt(Instant.parse("2026-03-31T00:00:00Z"))
                .build();

        String json = objectMapper.writeValueAsString(record);

        assertTrue(json.contains("\"artifactStreamId\":\"stream-1\""));
        assertTrue(json.contains("\"originArtifactStreamId\":\"stream-1\""));
        assertTrue(json.contains("\"contentRevisionId\":\"rev-2\""));
        assertTrue(json.contains("\"artifactSubtype\":\"tool_policy:usage\""));
    }
}
