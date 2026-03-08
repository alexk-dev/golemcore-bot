package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionPreparation;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompactionOrchestrationServiceTest {

    @Mock
    private SessionPort sessionPort;

    @Mock
    private CompactionPreparationService preparationService;

    @Mock
    private CompactionDetailsExtractor detailsExtractor;

    @Mock
    private CompactionService compactionService;

    @Mock
    private RuntimeConfigService runtimeConfigService;

    private CompactionOrchestrationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        service = new CompactionOrchestrationService(
                sessionPort,
                preparationService,
                detailsExtractor,
                compactionService,
                runtimeConfigService,
                clock);
    }

    @Test
    void shouldReturnMissingResultWhenSessionAbsent() {
        when(sessionPort.get("s-missing")).thenReturn(Optional.empty());

        CompactionResult result = service.compact("s-missing", CompactionReason.MANUAL_COMMAND, 10);

        assertEquals(-1, result.removed());
        assertFalse(result.usedSummary());
        assertNull(result.summaryMessage());
        assertNull(result.details());

        verify(preparationService, never()).prepare(any(), any(), any(Integer.class), any(), any(Boolean.class));
        verify(sessionPort, never()).save(any());
    }

    @Test
    void shouldPersistEmptyDetailsWhenNothingToCompact() {
        AgentSession session = sessionWithMessages("s1", List.of(user("u1"), assistant("a1")));
        when(sessionPort.get("s1")).thenReturn(Optional.of(session));
        when(runtimeConfigService.isCompactionPreserveTurnBoundariesEnabled()).thenReturn(true);
        when(runtimeConfigService.getCompactionDetailsMaxItemsPerCategory()).thenReturn(50);

        CompactionPreparation preparation = CompactionPreparation.builder()
                .sessionId("s1")
                .reason(CompactionReason.AUTO_THRESHOLD)
                .messagesToCompact(List.of())
                .messagesToKeep(List.of(user("u1"), assistant("a1")))
                .splitTurnDetected(true)
                .build();
        when(preparationService.prepare("s1", session.getMessages(), 5, CompactionReason.AUTO_THRESHOLD, true))
                .thenReturn(preparation);

        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(CompactionReason.AUTO_THRESHOLD)
                .summarizedCount(0)
                .keptCount(2)
                .usedLlmSummary(false)
                .summaryLength(0)
                .toolCount(0)
                .readFilesCount(0)
                .modifiedFilesCount(0)
                .durationMs(0)
                .toolNames(List.of())
                .readFiles(List.of())
                .modifiedFiles(List.of())
                .fileChanges(List.of())
                .splitTurnDetected(true)
                .fallbackUsed(false)
                .build();

        when(detailsExtractor.extract(
                eq(CompactionReason.AUTO_THRESHOLD),
                eq(List.of()),
                eq(0),
                eq(2),
                eq(false),
                eq(0),
                eq(true),
                eq(false),
                eq(0L),
                eq(50)))
                .thenReturn(details);

        CompactionResult result = service.compact("s1", CompactionReason.AUTO_THRESHOLD, 5);

        assertEquals(0, result.removed());
        assertFalse(result.usedSummary());
        assertNull(result.summaryMessage());
        assertEquals(details, result.details());

        assertNotNull(session.getMetadata());
        Object persisted = session.getMetadata().get(ContextAttributes.COMPACTION_LAST_DETAILS);
        assertTrue(persisted instanceof Map<?, ?>);
        verify(sessionPort, never()).save(any());
    }

    @Test
    void shouldCompactWithSummaryAndPersistMetadata() {
        Message m1 = user("u1");
        Message m2 = assistant("a1");
        Message m3 = user("u2");
        Message m4 = assistant("a2");
        AgentSession session = sessionWithMessages("s2", List.of(m1, m2, m3, m4));
        when(sessionPort.get("s2")).thenReturn(Optional.of(session));

        when(runtimeConfigService.isCompactionPreserveTurnBoundariesEnabled()).thenReturn(true);
        when(runtimeConfigService.getCompactionDetailsMaxItemsPerCategory()).thenReturn(25);
        when(runtimeConfigService.isCompactionDetailsEnabled()).thenReturn(true);

        CompactionPreparation preparation = CompactionPreparation.builder()
                .sessionId("s2")
                .reason(CompactionReason.MANUAL_COMMAND)
                .messagesToCompact(List.of(m1, m2))
                .messagesToKeep(List.of(m3, m4))
                .splitTurnDetected(false)
                .build();
        when(preparationService.prepare("s2", session.getMessages(), 2, CompactionReason.MANUAL_COMMAND, true))
                .thenReturn(preparation);

        when(compactionService.summarize(List.of(m1, m2))).thenReturn("short summary");
        Message summaryMessage = Message.builder()
                .role("system")
                .content("[Conversation summary]\nshort summary")
                .metadata(new LinkedHashMap<>())
                .build();
        when(compactionService.createSummaryMessage("short summary")).thenReturn(summaryMessage);

        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(CompactionReason.MANUAL_COMMAND)
                .summarizedCount(2)
                .keptCount(2)
                .usedLlmSummary(true)
                .summaryLength(13)
                .toolCount(1)
                .readFilesCount(1)
                .modifiedFilesCount(1)
                .durationMs(0)
                .toolNames(List.of("filesystem"))
                .readFiles(List.of("a.txt"))
                .modifiedFiles(List.of("b.txt"))
                .fileChanges(List.of(
                        CompactionDetails.FileChangeStat.builder()
                                .path("b.txt")
                                .addedLines(2)
                                .removedLines(0)
                                .deleted(false)
                                .build()))
                .splitTurnDetected(false)
                .fallbackUsed(false)
                .build();
        when(detailsExtractor.extract(
                eq(CompactionReason.MANUAL_COMMAND),
                eq(List.of(m1, m2)),
                eq(2),
                eq(2),
                eq(true),
                eq(13),
                eq(false),
                eq(false),
                eq(0L),
                eq(25)))
                .thenReturn(details);

        CompactionResult result = service.compact("s2", CompactionReason.MANUAL_COMMAND, 2);

        assertEquals(2, result.removed());
        assertTrue(result.usedSummary());
        assertEquals(summaryMessage, result.summaryMessage());
        assertEquals(details, result.details());

        assertEquals(3, session.getMessages().size());
        assertEquals("system", session.getMessages().get(0).getRole());

        Object summaryDetailsObject = summaryMessage.getMetadata().get("compactionDetails");
        assertTrue(summaryDetailsObject instanceof Map<?, ?>);
        Map<?, ?> summaryDetails = (Map<?, ?>) summaryDetailsObject;
        assertEquals("MANUAL_COMMAND", summaryDetails.get("reason"));
        assertEquals(2, summaryDetails.get("summarizedCount"));
        assertEquals(1, ((List<?>) summaryDetails.get("fileChanges")).size());

        Object persisted = session.getMetadata().get(ContextAttributes.COMPACTION_LAST_DETAILS);
        assertTrue(persisted instanceof Map<?, ?>);

        ArgumentCaptor<AgentSession> sessionCaptor = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionPort).save(sessionCaptor.capture());
        assertEquals("s2", sessionCaptor.getValue().getId());
    }

    @Test
    void shouldFallbackWhenSummaryBlankAndSkipSummaryMetadata() {
        Message m1 = user("u1");
        Message m2 = assistant("a1");
        AgentSession session = sessionWithMessages("s3", List.of(m1, m2));
        when(sessionPort.get("s3")).thenReturn(Optional.of(session));

        when(runtimeConfigService.isCompactionPreserveTurnBoundariesEnabled()).thenReturn(false);
        when(runtimeConfigService.getCompactionDetailsMaxItemsPerCategory()).thenReturn(10);

        CompactionPreparation preparation = CompactionPreparation.builder()
                .sessionId("s3")
                .reason(CompactionReason.CONTEXT_OVERFLOW_RECOVERY)
                .messagesToCompact(List.of(m1))
                .messagesToKeep(List.of(m2))
                .splitTurnDetected(true)
                .build();
        when(preparationService.prepare("s3", session.getMessages(), 1,
                CompactionReason.CONTEXT_OVERFLOW_RECOVERY, false))
                .thenReturn(preparation);

        when(compactionService.summarize(List.of(m1))).thenReturn("   ");

        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(CompactionReason.CONTEXT_OVERFLOW_RECOVERY)
                .summarizedCount(1)
                .keptCount(1)
                .usedLlmSummary(false)
                .summaryLength(3)
                .toolCount(0)
                .readFilesCount(0)
                .modifiedFilesCount(0)
                .durationMs(0)
                .toolNames(List.of())
                .readFiles(List.of())
                .modifiedFiles(List.of())
                .fileChanges(List.of())
                .splitTurnDetected(true)
                .fallbackUsed(true)
                .build();

        when(detailsExtractor.extract(
                eq(CompactionReason.CONTEXT_OVERFLOW_RECOVERY),
                eq(List.of(m1)),
                eq(1),
                eq(1),
                eq(false),
                eq(3),
                eq(true),
                eq(true),
                eq(0L),
                eq(10)))
                .thenReturn(details);

        CompactionResult result = service.compact("s3", CompactionReason.CONTEXT_OVERFLOW_RECOVERY, 1);

        assertEquals(1, result.removed());
        assertFalse(result.usedSummary());
        assertNull(result.summaryMessage());
        assertEquals(1, session.getMessages().size());
        assertEquals("assistant", session.getMessages().get(0).getRole());

        verify(compactionService, never()).createSummaryMessage(any());
        verify(sessionPort).save(any());
    }

    @Test
    void shouldNotAttachDetailsToSummaryMetadataWhenDetailsFeatureDisabled() {
        Message m1 = user("u1");
        Message m2 = assistant("a1");
        AgentSession session = sessionWithMessages("s4", List.of(m1, m2));
        when(sessionPort.get("s4")).thenReturn(Optional.of(session));

        when(runtimeConfigService.isCompactionPreserveTurnBoundariesEnabled()).thenReturn(true);
        when(runtimeConfigService.getCompactionDetailsMaxItemsPerCategory()).thenReturn(10);
        when(runtimeConfigService.isCompactionDetailsEnabled()).thenReturn(false);

        CompactionPreparation preparation = CompactionPreparation.builder()
                .sessionId("s4")
                .reason(CompactionReason.MANUAL_COMMAND)
                .messagesToCompact(List.of(m1))
                .messagesToKeep(List.of(m2))
                .splitTurnDetected(false)
                .build();
        when(preparationService.prepare("s4", session.getMessages(), 1, CompactionReason.MANUAL_COMMAND, true))
                .thenReturn(preparation);

        when(compactionService.summarize(List.of(m1))).thenReturn("sum");

        Message summaryMessage = Message.builder()
                .role("system")
                .content("[Conversation summary]\nsum")
                .metadata(new LinkedHashMap<>())
                .build();
        when(compactionService.createSummaryMessage("sum")).thenReturn(summaryMessage);

        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(CompactionReason.MANUAL_COMMAND)
                .summarizedCount(1)
                .keptCount(1)
                .usedLlmSummary(true)
                .summaryLength(3)
                .toolCount(0)
                .readFilesCount(0)
                .modifiedFilesCount(0)
                .durationMs(0)
                .toolNames(List.of())
                .readFiles(List.of())
                .modifiedFiles(List.of())
                .fileChanges(List.of())
                .splitTurnDetected(false)
                .fallbackUsed(false)
                .build();

        when(detailsExtractor.extract(any(), any(), any(Integer.class), any(Integer.class), any(Boolean.class),
                any(Integer.class), any(Boolean.class), any(Boolean.class), any(Long.class), any(Integer.class)))
                .thenReturn(details);

        CompactionResult result = service.compact("s4", CompactionReason.MANUAL_COMMAND, 1);

        assertTrue(result.usedSummary());
        assertFalse(summaryMessage.getMetadata().containsKey("compactionDetails"));
        assertTrue(session.getMetadata().containsKey(ContextAttributes.COMPACTION_LAST_DETAILS));
    }

    private AgentSession sessionWithMessages(String id, List<Message> messages) {
        return AgentSession.builder()
                .id(id)
                .messages(new ArrayList<>(messages))
                .metadata(new LinkedHashMap<>())
                .build();
    }

    private Message user(String content) {
        return Message.builder()
                .role("user")
                .content(content)
                .build();
    }

    private Message assistant(String content) {
        return Message.builder()
                .role("assistant")
                .content(content)
                .build();
    }
}
