package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionPreparation;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central compaction pipeline: prepare boundaries, summarize, persist details.
 */
@Service
public class CompactionOrchestrationService {

    private static final String METADATA_KEY_COMPACTION_DETAILS = "compactionDetails";

    private final SessionPort sessionPort;
    private final CompactionPreparationService preparationService;
    private final CompactionDetailsExtractor detailsExtractor;
    private final CompactionService compactionService;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;

    public CompactionOrchestrationService(SessionPort sessionPort,
            CompactionPreparationService preparationService,
            CompactionDetailsExtractor detailsExtractor,
            CompactionService compactionService,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.sessionPort = sessionPort;
        this.preparationService = preparationService;
        this.detailsExtractor = detailsExtractor;
        this.compactionService = compactionService;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    public CompactionResult compact(String sessionId, CompactionReason reason, int keepLast) {
        Optional<AgentSession> sessionOptional = sessionPort.get(sessionId);
        if (sessionOptional.isEmpty()) {
            return CompactionResult.builder()
                    .removed(-1)
                    .usedSummary(false)
                    .summaryMessage(null)
                    .details(null)
                    .build();
        }

        AgentSession session = sessionOptional.get();
        List<Message> sessionMessages = session.getMessages() != null ? session.getMessages() : new ArrayList<>();

        CompactionPreparation preparation = preparationService.prepare(
                sessionId,
                sessionMessages,
                keepLast,
                reason,
                runtimeConfigService.isCompactionPreserveTurnBoundariesEnabled());

        if (preparation.messagesToCompact().isEmpty()) {
            CompactionDetails emptyDetails = detailsExtractor.extract(
                    reason,
                    List.of(),
                    0,
                    preparation.messagesToKeep().size(),
                    false,
                    0,
                    preparation.splitTurnDetected(),
                    false,
                    0,
                    runtimeConfigService.getCompactionDetailsMaxItemsPerCategory());
            persistDetails(session, emptyDetails);
            return CompactionResult.builder()
                    .removed(0)
                    .usedSummary(false)
                    .summaryMessage(null)
                    .details(emptyDetails)
                    .build();
        }

        long startedAt = clock.millis();
        String summary = compactionService.summarize(preparation.messagesToCompact());
        boolean usedSummary = summary != null && !summary.isBlank();
        boolean fallbackUsed = !usedSummary;

        Message summaryMessage = null;
        if (usedSummary) {
            summaryMessage = compactionService.createSummaryMessage(summary);
        }

        List<Message> keptMessages = Message.flattenToolMessages(new ArrayList<>(preparation.messagesToKeep()));
        sessionMessages.clear();
        if (summaryMessage != null) {
            sessionMessages.add(summaryMessage);
        }
        sessionMessages.addAll(keptMessages);

        long durationMs = Math.max(0, clock.millis() - startedAt);
        CompactionDetails details = detailsExtractor.extract(
                reason,
                preparation.messagesToCompact(),
                preparation.messagesToCompact().size(),
                keptMessages.size(),
                usedSummary,
                summary != null ? summary.length() : 0,
                preparation.splitTurnDetected(),
                fallbackUsed,
                durationMs,
                runtimeConfigService.getCompactionDetailsMaxItemsPerCategory());

        if (summaryMessage != null && runtimeConfigService.isCompactionDetailsEnabled()) {
            Map<String, Object> metadata = summaryMessage.getMetadata() != null
                    ? new LinkedHashMap<>(summaryMessage.getMetadata())
                    : new LinkedHashMap<>();
            metadata.put(METADATA_KEY_COMPACTION_DETAILS, toDetailsMap(details));
            summaryMessage.setMetadata(metadata);
        }

        persistDetails(session, details);
        sessionPort.save(session);

        return CompactionResult.builder()
                .removed(preparation.messagesToCompact().size())
                .usedSummary(usedSummary)
                .summaryMessage(summaryMessage)
                .details(details)
                .build();
    }

    private void persistDetails(AgentSession session, CompactionDetails details) {
        if (session == null || details == null) {
            return;
        }
        if (session.getMetadata() == null) {
            session.setMetadata(new LinkedHashMap<>());
        }
        session.getMetadata().put(ContextAttributes.COMPACTION_LAST_DETAILS, toDetailsMap(details));
    }

    private Map<String, Object> toDetailsMap(CompactionDetails details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", details.schemaVersion());
        result.put("reason", details.reason() != null ? details.reason().name() : null);
        result.put("summarizedCount", details.summarizedCount());
        result.put("keptCount", details.keptCount());
        result.put("usedLlmSummary", details.usedLlmSummary());
        result.put("summaryLength", details.summaryLength());
        result.put("toolCount", details.toolCount());
        result.put("readFilesCount", details.readFilesCount());
        result.put("modifiedFilesCount", details.modifiedFilesCount());
        result.put("durationMs", details.durationMs());
        result.put("toolNames", details.toolNames());
        result.put("readFiles", details.readFiles());
        result.put("modifiedFiles", details.modifiedFiles());
        result.put("splitTurnDetected", details.splitTurnDetected());
        result.put("fallbackUsed", details.fallbackUsed());

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
        result.put("fileChanges", fileChanges);

        return result;
    }
}
