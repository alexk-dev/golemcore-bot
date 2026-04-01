package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactNormalizedRevisionProjectionServiceTest {

    private ArtifactNormalizedRevisionProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ArtifactNormalizedRevisionProjectionService();
    }

    @Test
    void shouldReturnNullWhenRevisionRecordIsMissing() {
        assertNull(service.normalize(null));
    }

    @Test
    void shouldNormalizeContentHashAndSectionsFromRawArtifactRevision() {
        Instant createdAt = Instant.parse("2026-03-31T20:30:00Z");
        ArtifactRevisionRecord record = ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-1")
                .rawContent("  line one  \n\n line two \n")
                .createdAt(createdAt)
                .build();

        ArtifactNormalizedRevisionProjection projection = service.normalize(record);

        assertEquals("stream-1", projection.getArtifactStreamId());
        assertEquals("rev-1", projection.getContentRevisionId());
        assertEquals("line one\nline two", projection.getNormalizedContent());
        assertEquals(createdAt, projection.getProjectedAt());
        assertEquals(2, projection.getSemanticSections().size());
        assertEquals("line one", projection.getSemanticSections().getFirst());
        assertEquals("line two", projection.getSemanticSections().get(1));
        assertNotNull(projection.getNormalizedHash());
        assertEquals(64, projection.getNormalizedHash().length());
        assertTrue(projection.getNormalizedHash().chars().allMatch(ch -> Character.digit(ch, 16) >= 0));
    }

    @Test
    void shouldUseEmptyContentAndCurrentTimestampWhenRawContentAndCreatedAtAreMissing() {
        ArtifactRevisionRecord record = ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-2")
                .contentRevisionId("rev-2")
                .rawContent(null)
                .createdAt(null)
                .build();

        ArtifactNormalizedRevisionProjection projection = service.normalize(record);

        assertEquals("", projection.getNormalizedContent());
        assertEquals(0, projection.getSemanticSections().size());
        assertNotNull(projection.getProjectedAt());
        assertNotEquals(Instant.EPOCH, projection.getProjectedAt());
    }
}
