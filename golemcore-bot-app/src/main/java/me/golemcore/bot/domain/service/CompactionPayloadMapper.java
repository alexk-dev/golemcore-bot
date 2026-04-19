package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical shape for the {@code compaction.last.details} attribute. All write
 * sites (AutoCompactionSystem, CompactionOrchestrationService, preflight) must
 * emit the same structure so dashboard/telemetry consumers can parse it without
 * branching on the origin.
 *
 * <p>
 * Publication ownership: the persistent write (session metadata) is done inside
 * {@link CompactionOrchestrationService#compact}. Per-turn callers should use
 * {@link #publishToContext} when same-turn downstream systems also need the
 * payload exposed through {@link AgentContext#getAttribute}; that keeps one
 * code path responsible for building the payload.
 */
public final class CompactionPayloadMapper {

    private CompactionPayloadMapper() {
    }

    /**
     * Mirror the compaction payload onto the per-turn {@link AgentContext}
     * attributes. Intended for systems that already hold a live context and need
     * same-turn downstream consumers (e.g. dashboard routing, turn outcome) to see
     * the details without reading session metadata.
     *
     * <p>
     * This does not replace the persistent write performed by
     * {@link CompactionOrchestrationService#compact} - session metadata remains the
     * source of truth; this method only exposes a transient copy.
     */
    public static void publishToContext(AgentContext context, CompactionResult result) {
        if (context == null || result == null || result.details() == null) {
            return;
        }
        context.setAttribute(ContextAttributes.COMPACTION_LAST_DETAILS, toPayload(result));
    }

    /**
     * Materialize a {@link CompactionResult} as the canonical flat payload that
     * callers write into session metadata / context attributes. Null-safe: returns
     * an empty map for a null result so downstream key lookups stay total.
     */
    public static Map<String, Object> toPayload(CompactionResult result) {
        if (result == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("removed", result.removed());
        payload.put("usedSummary", result.usedSummary());
        if (result.details() != null) {
            payload.putAll(toDetailsMap(result.details()));
        }
        return payload;
    }

    /**
     * Flatten a {@link CompactionDetails} record into the canonical string-keyed
     * map accepted by session metadata, context attributes, and dashboards.
     * Null-safe: returns an empty map for null input. Enum fields are emitted as
     * {@code name()} so the output is JSON-stable without a custom serializer.
     */
    public static Map<String, Object> toDetailsMap(CompactionDetails details) {
        if (details == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", details.schemaVersion());
        map.put("reason", details.reason() != null ? details.reason().name() : null);
        map.put("summarizedCount", details.summarizedCount());
        map.put("keptCount", details.keptCount());
        map.put("usedLlmSummary", details.usedLlmSummary());
        map.put("summaryLength", details.summaryLength());
        map.put("toolCount", details.toolCount());
        map.put("readFilesCount", details.readFilesCount());
        map.put("modifiedFilesCount", details.modifiedFilesCount());
        map.put("durationMs", details.durationMs());
        map.put("toolNames", details.toolNames());
        map.put("readFiles", details.readFiles());
        map.put("modifiedFiles", details.modifiedFiles());
        map.put("splitTurnDetected", details.splitTurnDetected());
        map.put("fallbackUsed", details.fallbackUsed());

        List<Map<String, Object>> fileChanges = new ArrayList<>();
        if (details.fileChanges() != null) {
            for (CompactionDetails.FileChangeStat fileChange : details.fileChanges()) {
                Map<String, Object> fileChangeMap = new LinkedHashMap<>();
                fileChangeMap.put("path", fileChange.path());
                fileChangeMap.put("addedLines", fileChange.addedLines());
                fileChangeMap.put("removedLines", fileChange.removedLines());
                fileChangeMap.put("deleted", fileChange.deleted());
                fileChanges.add(fileChangeMap);
            }
        }
        map.put("fileChanges", fileChanges);
        return map;
    }
}
