package me.golemcore.bot.adapter.outbound.selfevolving;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.selfevolving.ArtifactRepositoryPort;
import org.springframework.stereotype.Component;

/**
 * JSON-on-StoragePort adapter for the self-evolving artifact repository. Owns
 * directory layout, file names, and the atomic write policy for revisions.
 */
@Component
@Slf4j
public class JsonArtifactRepositoryAdapter implements ArtifactRepositoryPort {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String BUNDLES_FILE = "artifact-bundles.json";
    private static final String ARTIFACT_REVISIONS_FILE = "artifact-revisions.json";
    private static final TypeReference<List<ArtifactBundleRecord>> BUNDLE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ArtifactRevisionRecord>> ARTIFACT_REVISION_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public JsonArtifactRepositoryAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<ArtifactBundleRecord> loadBundles() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, BUNDLES_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<ArtifactBundleRecord> bundles = objectMapper.readValue(json, BUNDLE_LIST_TYPE);
            return bundles != null ? new ArrayList<>(bundles) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load artifact bundles: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void saveBundles(List<ArtifactBundleRecord> bundles) {
        try {
            String json = objectMapper.writeValueAsString(bundles);
            storagePort.putText(SELF_EVOLVING_DIR, BUNDLES_FILE, json).join();
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist artifact bundles", e);
        }
    }

    @Override
    public List<ArtifactRevisionRecord> loadRevisions() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, ARTIFACT_REVISIONS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<ArtifactRevisionRecord> records = objectMapper.readValue(json, ARTIFACT_REVISION_LIST_TYPE);
            return records != null ? new ArrayList<>(records) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load artifact revisions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void saveRevisions(List<ArtifactRevisionRecord> revisions) {
        try {
            String json = objectMapper.writeValueAsString(revisions);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, ARTIFACT_REVISIONS_FILE, json, true).join();
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist artifact revisions", e);
        }
    }

    @Override
    public void writeWorkspaceProjection(String relativePath, Object payload) {
        try {
            storagePort.putText(SELF_EVOLVING_DIR, relativePath, objectMapper.writeValueAsString(payload)).join();
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to rebuild artifact workspace projections at " + relativePath, e);
        }
    }
}
