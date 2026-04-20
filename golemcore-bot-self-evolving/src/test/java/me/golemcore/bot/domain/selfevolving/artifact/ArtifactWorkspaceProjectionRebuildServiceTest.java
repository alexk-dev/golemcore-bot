package me.golemcore.bot.domain.selfevolving.artifact;

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.golemcore.bot.adapter.outbound.selfevolving.JsonArtifactRepositoryAdapter;

class ArtifactWorkspaceProjectionRebuildServiceTest {

    private StoragePort storagePort;
    private ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService;
    private ArtifactWorkspaceProjectionRebuildService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        artifactWorkspaceProjectionService = mock(ArtifactWorkspaceProjectionService.class);
        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new ArtifactWorkspaceProjectionRebuildService(artifactWorkspaceProjectionService,
                new JsonArtifactRepositoryAdapter(storagePort),
                Clock.fixed(Instant.parse("2026-03-31T21:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldRebuildCatalogAndLineageProjectionsWithSchemaVersionMetadata() {
        when(artifactWorkspaceProjectionService.listCatalog()).thenReturn(List.of(ArtifactCatalogEntry.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .projectionSchemaVersion(1)
                .build()));
        when(artifactWorkspaceProjectionService.getLineage("stream-1")).thenReturn(ArtifactLineageProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .projectionSchemaVersion(1)
                .build());

        service.rebuildAll();

        verify(storagePort).putTextAtomic(eq("self-evolving"), eq("artifact-workspace/catalog.json"), anyString(),
                eq(false));
        verify(storagePort).putTextAtomic(eq("self-evolving"), eq("artifact-workspace/lineage/stream-1.json"),
                anyString(), eq(false));
    }
}
