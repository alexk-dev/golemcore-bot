package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Rebuilds materialized artifact workspace projections.
 */
@Service
public class ArtifactWorkspaceProjectionRebuildService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String ARTIFACT_WORKSPACE_DIR = "artifact-workspace";

    private final ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService;
    private final StoragePort storagePort;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ArtifactWorkspaceProjectionRebuildService(
            ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService,
            StoragePort storagePort,
            Clock clock) {
        this.artifactWorkspaceProjectionService = artifactWorkspaceProjectionService;
        this.storagePort = storagePort;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void rebuildAll() {
        List<ArtifactCatalogEntry> catalogEntries = artifactWorkspaceProjectionService.listCatalog();
        putJson(ARTIFACT_WORKSPACE_DIR + "/catalog.json", catalogEntries);
        for (ArtifactCatalogEntry catalogEntry : catalogEntries) {
            ArtifactLineageProjection lineageProjection = artifactWorkspaceProjectionService
                    .getLineage(catalogEntry.getArtifactStreamId());
            putJson(ARTIFACT_WORKSPACE_DIR + "/lineage/" + catalogEntry.getArtifactStreamId() + ".json",
                    lineageProjection);
        }
    }

    private void putJson(String path, Object payload) {
        try {
            storagePort.putText(SELF_EVOLVING_DIR, path, objectMapper.writeValueAsString(payload)).join();
        } catch (Exception exception) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to rebuild artifact workspace projections at " + path, exception);
        }
    }
}
