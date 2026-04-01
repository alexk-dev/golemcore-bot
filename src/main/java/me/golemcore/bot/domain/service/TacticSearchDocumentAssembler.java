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

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds normalized lexical and semantic search documents from tactic records.
 */
@Service
public class TacticSearchDocumentAssembler {

    public TacticIndexDocument assemble(TacticRecord tacticRecord) {
        TacticRecord record = tacticRecord != null ? tacticRecord : new TacticRecord();
        return TacticIndexDocument.builder()
                .tacticId(record.getTacticId())
                .artifactStreamId(record.getArtifactStreamId())
                .originArtifactStreamId(record.getOriginArtifactStreamId())
                .artifactKey(record.getArtifactKey())
                .artifactType(record.getArtifactType())
                .title(record.getTitle())
                .aliases(copy(record.getAliases()))
                .contentRevisionId(record.getContentRevisionId())
                .intentSummary(record.getIntentSummary())
                .behaviorSummary(record.getBehaviorSummary())
                .toolSummary(record.getToolSummary())
                .outcomeSummary(record.getOutcomeSummary())
                .benchmarkSummary(record.getBenchmarkSummary())
                .approvalNotes(record.getApprovalNotes())
                .evidenceSnippets(copy(record.getEvidenceSnippets()))
                .taskFamilies(copy(record.getTaskFamilies()))
                .tags(copy(record.getTags()))
                .lexicalText(buildLexicalText(record))
                .semanticText(buildSemanticText(record))
                .promotionState(record.getPromotionState())
                .rolloutStage(record.getRolloutStage())
                .successRate(record.getSuccessRate())
                .benchmarkWinRate(record.getBenchmarkWinRate())
                .regressionFlags(copy(record.getRegressionFlags()))
                .recencyScore(record.getRecencyScore())
                .golemLocalUsageSuccess(record.getGolemLocalUsageSuccess())
                .embeddingStatus(record.getEmbeddingStatus())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private String buildLexicalText(TacticRecord record) {
        List<String> segments = new ArrayList<>();
        append(segments, record.getTitle());
        append(segments, record.getAliases());
        append(segments, record.getArtifactKey());
        append(segments, record.getIntentSummary());
        append(segments, record.getBehaviorSummary());
        append(segments, record.getToolSummary());
        append(segments, record.getOutcomeSummary());
        append(segments, record.getBenchmarkSummary());
        append(segments, record.getApprovalNotes());
        append(segments, record.getEvidenceSnippets());
        append(segments, record.getTaskFamilies());
        append(segments, record.getTags());
        return normalize(String.join(" ", segments));
    }

    private String buildSemanticText(TacticRecord record) {
        List<String> segments = new ArrayList<>();
        append(segments, record.getIntentSummary());
        append(segments, record.getBehaviorSummary());
        append(segments, record.getToolSummary());
        append(segments, record.getOutcomeSummary());
        append(segments, record.getBenchmarkSummary());
        append(segments, record.getEvidenceSnippets());
        return normalize(String.join(" ", segments));
    }

    private void append(List<String> segments, String value) {
        if (!StringValueSupport.isBlank(value)) {
            segments.add(value.trim());
        }
    }

    private void append(List<String> segments, List<String> values) {
        if (values == null) {
            return;
        }
        values.stream()
                .filter(value -> !StringValueSupport.isBlank(value))
                .map(String::trim)
                .forEach(segments::add);
    }

    private List<String> copy(List<String> values) {
        return values != null ? new ArrayList<>(values) : new ArrayList<>();
    }

    private String normalize(String value) {
        if (StringValueSupport.isBlank(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
