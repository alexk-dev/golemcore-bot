package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class TraceBudgetService {

    public void enforceBudget(AgentSession session, long maxCompressedBytes) {
        if (session == null || session.getTraces() == null) {
            return;
        }
        if (maxCompressedBytes < 0L) {
            throw new IllegalArgumentException("maxCompressedBytes must be non-negative");
        }
        TraceStorageStats stats = ensureStorageStats(session);
        long currentCompressedBytes = calculateCompressedBytes(session);
        if (currentCompressedBytes <= maxCompressedBytes) {
            stats.setCompressedSnapshotBytes(currentCompressedBytes);
            stats.setUncompressedSnapshotBytes(calculateUncompressedBytes(session));
            stats.setTruncatedTraces(countTruncatedTraces(session));
            return;
        }

        List<SnapshotRef> snapshotRefs = new ArrayList<>();
        for (TraceRecord trace : session.getTraces()) {
            if (trace == null || trace.getSpans() == null) {
                continue;
            }
            for (TraceSpanRecord span : trace.getSpans()) {
                if (span == null || span.getSnapshots() == null) {
                    continue;
                }
                for (TraceSnapshot snapshot : span.getSnapshots()) {
                    if (snapshot != null) {
                        snapshotRefs.add(new SnapshotRef(trace, span, snapshot));
                    }
                }
            }
        }

        snapshotRefs.sort(Comparator.comparing((SnapshotRef ref) -> ref.trace().getStartedAt(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (SnapshotRef snapshotRef : snapshotRefs) {
            if (currentCompressedBytes <= maxCompressedBytes) {
                break;
            }
            TraceSpanRecord span = snapshotRef.span();
            TraceSnapshot snapshot = snapshotRef.snapshot();
            if (span.getSnapshots().remove(snapshot)) {
                currentCompressedBytes -= safeLong(snapshot.getCompressedSize());
                snapshotRef.trace().setTruncated(true);
                stats.setEvictedSnapshots(stats.getEvictedSnapshots() + 1);
            }
        }

        refreshStats(session, stats);
    }

    public void enforceTraceCountLimit(AgentSession session, int maxTraces) {
        if (session == null || session.getTraces() == null) {
            return;
        }
        if (maxTraces < 1) {
            throw new IllegalArgumentException("maxTraces must be positive");
        }
        TraceStorageStats stats = ensureStorageStats(session);
        if (session.getTraces().size() <= maxTraces) {
            refreshStats(session, stats);
            return;
        }

        session.getTraces().sort(Comparator.comparing(
                TraceRecord::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        while (session.getTraces().size() > maxTraces) {
            session.getTraces().remove(0);
            stats.setEvictedTraces(stats.getEvictedTraces() + 1);
        }

        refreshStats(session, stats);
    }

    private void recalculateTraceSnapshotTotals(AgentSession session) {
        for (TraceRecord trace : session.getTraces()) {
            if (trace == null || trace.getSpans() == null) {
                continue;
            }
            long compressed = 0L;
            long uncompressed = 0L;
            for (TraceSpanRecord span : trace.getSpans()) {
                if (span == null || span.getSnapshots() == null) {
                    continue;
                }
                for (TraceSnapshot snapshot : span.getSnapshots()) {
                    if (snapshot == null) {
                        continue;
                    }
                    compressed += safeLong(snapshot.getCompressedSize());
                    uncompressed += safeLong(snapshot.getOriginalSize());
                }
            }
            trace.setCompressedSnapshotBytes(compressed);
            trace.setUncompressedSnapshotBytes(uncompressed);
        }
    }

    private void refreshStats(AgentSession session, TraceStorageStats stats) {
        recalculateTraceSnapshotTotals(session);
        stats.setCompressedSnapshotBytes(calculateCompressedBytes(session));
        stats.setUncompressedSnapshotBytes(calculateUncompressedBytes(session));
        stats.setTruncatedTraces(countTruncatedTraces(session));
    }

    private long calculateCompressedBytes(AgentSession session) {
        long total = 0L;
        for (TraceRecord trace : session.getTraces()) {
            if (trace != null) {
                total += safeLong(trace.getCompressedSnapshotBytes());
            }
        }
        return total;
    }

    private long calculateUncompressedBytes(AgentSession session) {
        long total = 0L;
        for (TraceRecord trace : session.getTraces()) {
            if (trace != null) {
                total += safeLong(trace.getUncompressedSnapshotBytes());
            }
        }
        return total;
    }

    private int countTruncatedTraces(AgentSession session) {
        int total = 0;
        for (TraceRecord trace : session.getTraces()) {
            if (trace != null && trace.isTruncated()) {
                total++;
            }
        }
        return total;
    }

    private TraceStorageStats ensureStorageStats(AgentSession session) {
        if (session.getTraceStorageStats() == null) {
            session.setTraceStorageStats(new TraceStorageStats());
        }
        return session.getTraceStorageStats();
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private record SnapshotRef(TraceRecord trace, TraceSpanRecord span, TraceSnapshot snapshot) {
    }
}
