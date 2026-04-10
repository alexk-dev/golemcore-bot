package me.golemcore.bot.domain.selfevolving.tactic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.selfevolving.TacticRecordStorePort;

public class InMemoryTacticRecordStorePort implements TacticRecordStorePort {

    private final ConcurrentMap<String, TacticRecord> records = new ConcurrentHashMap<>();

    @Override
    public List<TacticRecord> loadAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(TacticRecord::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::copy)
                .toList();
    }

    @Override
    public void save(TacticRecord record) {
        records.put(record.getTacticId(), copy(record));
    }

    @Override
    public void delete(String tacticId) {
        records.remove(tacticId);
    }

    public void seed(TacticRecord record) {
        save(record);
    }

    public boolean contains(String tacticId) {
        return records.containsKey(tacticId);
    }

    private TacticRecord copy(TacticRecord record) {
        return TacticRecord.builder()
                .tacticId(record.getTacticId())
                .artifactStreamId(record.getArtifactStreamId())
                .originArtifactStreamId(record.getOriginArtifactStreamId())
                .artifactKey(record.getArtifactKey())
                .artifactType(record.getArtifactType())
                .title(record.getTitle())
                .aliases(record.getAliases() != null ? new ArrayList<>(record.getAliases()) : new ArrayList<>())
                .contentRevisionId(record.getContentRevisionId())
                .intentSummary(record.getIntentSummary())
                .behaviorSummary(record.getBehaviorSummary())
                .toolSummary(record.getToolSummary())
                .outcomeSummary(record.getOutcomeSummary())
                .benchmarkSummary(record.getBenchmarkSummary())
                .approvalNotes(record.getApprovalNotes())
                .evidenceSnippets(record.getEvidenceSnippets() != null ? new ArrayList<>(record.getEvidenceSnippets()) : new ArrayList<>())
                .taskFamilies(record.getTaskFamilies() != null ? new ArrayList<>(record.getTaskFamilies()) : new ArrayList<>())
                .tags(record.getTags() != null ? new ArrayList<>(record.getTags()) : new ArrayList<>())
                .promotionState(record.getPromotionState())
                .rolloutStage(record.getRolloutStage())
                .successRate(record.getSuccessRate())
                .benchmarkWinRate(record.getBenchmarkWinRate())
                .regressionFlags(record.getRegressionFlags() != null ? new ArrayList<>(record.getRegressionFlags()) : new ArrayList<>())
                .recencyScore(record.getRecencyScore())
                .golemLocalUsageSuccess(record.getGolemLocalUsageSuccess())
                .embeddingStatus(record.getEmbeddingStatus())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
