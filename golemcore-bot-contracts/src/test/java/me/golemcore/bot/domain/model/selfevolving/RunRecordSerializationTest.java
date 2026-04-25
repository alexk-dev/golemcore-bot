package me.golemcore.bot.domain.model.selfevolving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRecordSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldSerializeRunRecordWithArtifactBundleAndVerdictRefs() throws Exception {
        RunRecord record = RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .artifactBundleId("bundle-1")
                .verdictId("verdict-1")
                .build();

        String json = objectMapper.writeValueAsString(record);

        assertTrue(json.contains("\"artifactBundleId\":\"bundle-1\""));
        assertTrue(json.contains("\"verdictId\":\"verdict-1\""));
    }
}
