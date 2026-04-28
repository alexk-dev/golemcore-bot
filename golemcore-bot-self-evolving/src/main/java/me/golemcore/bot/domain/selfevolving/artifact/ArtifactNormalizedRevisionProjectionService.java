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

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import me.golemcore.bot.domain.support.StringValueSupport;

/**
 * Builds normalized artifact revision projections for diffing.
 */
@Service
public class ArtifactNormalizedRevisionProjectionService {

    private static final int NORMALIZATION_SCHEMA_VERSION = 1;

    public ArtifactNormalizedRevisionProjection normalize(ArtifactRevisionRecord record) {
        if (record == null) {
            return null;
        }
        String normalizedContent = normalizeContent(record.getRawContent());
        return ArtifactNormalizedRevisionProjection.builder()
                .artifactStreamId(record.getArtifactStreamId())
                .contentRevisionId(record.getContentRevisionId())
                .normalizationSchemaVersion(NORMALIZATION_SCHEMA_VERSION)
                .normalizedContent(normalizedContent)
                .normalizedHash(hash(normalizedContent))
                .semanticSections(extractSections(normalizedContent))
                .projectedAt(record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now())
                .build();
    }

    private String normalizeContent(String rawContent) {
        if (rawContent == null) {
            return "";
        }
        List<String> lines = rawContent.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        return String.join("\n", lines);
    }

    private List<String> extractSections(String normalizedContent) {
        if (StringValueSupport.isBlank(normalizedContent)) {
            return List.of();
        }
        List<String> sections = new ArrayList<>();
        for (String line : normalizedContent.split("\\n")) {
            sections.add(line);
        }
        return sections;
    }

    private String hash(String normalizedContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalizedContent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
