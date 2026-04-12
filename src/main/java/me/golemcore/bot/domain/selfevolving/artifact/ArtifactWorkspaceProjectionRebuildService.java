package me.golemcore.bot.domain.selfevolving.artifact;

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

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.port.outbound.selfevolving.ArtifactRepositoryPort;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Rebuilds materialized artifact workspace projections.
 */
@Service
public class ArtifactWorkspaceProjectionRebuildService {

    private static final String ARTIFACT_WORKSPACE_DIR = "artifact-workspace";

    private final ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService;
    private final ArtifactRepositoryPort artifactRepository;

    public ArtifactWorkspaceProjectionRebuildService(
            ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService,
            ArtifactRepositoryPort artifactRepository,
            Clock clock) {
        this.artifactWorkspaceProjectionService = artifactWorkspaceProjectionService;
        this.artifactRepository = artifactRepository;
    }

    public void rebuildAll() {
        List<ArtifactCatalogEntry> catalogEntries = artifactWorkspaceProjectionService.listCatalog();
        artifactRepository.writeWorkspaceProjection(ARTIFACT_WORKSPACE_DIR + "/catalog.json", catalogEntries);
        for (ArtifactCatalogEntry catalogEntry : catalogEntries) {
            ArtifactLineageProjection lineageProjection = artifactWorkspaceProjectionService
                    .getLineage(catalogEntry.getArtifactStreamId());
            artifactRepository.writeWorkspaceProjection(
                    ARTIFACT_WORKSPACE_DIR + "/lineage/" + catalogEntry.getArtifactStreamId() + ".json",
                    lineageProjection);
        }
    }
}
