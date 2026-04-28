package me.golemcore.bot.domain.context.compaction;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.CompactionDetails;
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden contract test: locks the exact shape of COMPACTION_LAST_DETAILS. Any
 * field added/removed/renamed forces an explicit update here so downstream
 * dashboards/consumers cannot drift silently. When this test breaks, ask
 * whether every reader (dashboard, telemetry, tests of AutoCompactionSystem /
 * preflight) was also updated.
 */
class CompactionPayloadMapperTest {

    private static final List<String> EXPECTED_PAYLOAD_KEYS = List.of(
            "removed",
            "usedSummary",
            "schemaVersion",
            "reason",
            "summarizedCount",
            "keptCount",
            "usedLlmSummary",
            "summaryLength",
            "toolCount",
            "readFilesCount",
            "modifiedFilesCount",
            "durationMs",
            "toolNames",
            "readFiles",
            "modifiedFiles",
            "splitTurnDetected",
            "fallbackUsed",
            "fileChanges");

    @Test
    void toPayload_shouldEmitCanonicalKeySetInOrder() {
        CompactionResult result = sampleResult();

        Map<String, Object> payload = CompactionPayloadMapper.toPayload(result);

        assertEquals(EXPECTED_PAYLOAD_KEYS, List.copyOf(payload.keySet()),
                "Payload key set/order is a public contract - update every reader if this changes");
    }

    @Test
    void toPayload_shouldFlattenFileChangesIntoMapList() {
        CompactionResult result = sampleResult();

        Map<String, Object> payload = CompactionPayloadMapper.toPayload(result);

        Object fileChanges = payload.get("fileChanges");
        assertInstanceOf(List.class, fileChanges);
        List<?> fileChangeList = (List<?>) fileChanges;
        assertEquals(1, fileChangeList.size());
        Object firstChange = fileChangeList.get(0);
        assertInstanceOf(Map.class, firstChange);
        Map<?, ?> firstChangeMap = (Map<?, ?>) firstChange;
        assertEquals("src/Foo.java", firstChangeMap.get("path"));
        assertEquals(3, firstChangeMap.get("addedLines"));
        assertEquals(1, firstChangeMap.get("removedLines"));
        assertEquals(false, firstChangeMap.get("deleted"));
    }

    @Test
    void toPayload_shouldReturnMutableEmptyMapForNullResult() {
        Map<String, Object> payload = CompactionPayloadMapper.toPayload(null);

        assertNotNull(payload);
        assertTrue(payload.isEmpty());
        // Mutability is part of the contract: callers may put extra diagnostic
        // fields before handing the payload downstream. Returning Map.of() here
        // would UnsupportedOperationException in that path.
        payload.put("probe", "ok");
        assertEquals("ok", payload.get("probe"));
    }

    @Test
    void toPayload_shouldEmitRemovedAndUsedSummaryWhenDetailsAreNull() {
        // Guards the documented contract: a CompactionResult with null details
        // still produces a meaningful payload (just the two top-level fields).
        // publishToContext explicitly drops this case to avoid noisy attribute
        // writes, but toPayload stays symmetric so callers that want the
        // partial shape can still get it.
        CompactionResult partial = CompactionResult.builder()
                .removed(7)
                .usedSummary(false)
                .details(null)
                .build();

        Map<String, Object> payload = CompactionPayloadMapper.toPayload(partial);

        assertEquals(7, payload.get("removed"));
        assertEquals(false, payload.get("usedSummary"));
        assertNull(payload.get("schemaVersion"),
                "null-details result must not populate detail fields");
    }

    @Test
    void toDetailsMap_shouldEmitNullReasonAsNullInMap() {
        // Locks the "details present but reason null" branch: callers must
        // never see a broken enum.name() lookup, they get a literal null so
        // downstream serializers can distinguish "unknown reason" from
        // "reason key missing".
        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(null)
                .fileChanges(List.of())
                .build();

        Map<String, Object> map = CompactionPayloadMapper.toDetailsMap(details);

        assertTrue(map.containsKey("reason"),
                "reason key must always be present in the canonical shape");
        assertNull(map.get("reason"));
    }

    @Test
    void toDetailsMap_shouldEmitEmptyFileChangesListWhenSourceIsNull() {
        // Locks the "details.fileChanges() == null" branch: the payload must
        // still publish an (empty) fileChanges list so dashboards can rely on
        // a uniform shape instead of branching on presence.
        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(CompactionReason.AUTO_THRESHOLD)
                .fileChanges(null)
                .build();

        Map<String, Object> map = CompactionPayloadMapper.toDetailsMap(details);

        Object fileChanges = map.get("fileChanges");
        assertInstanceOf(List.class, fileChanges);
        assertTrue(((List<?>) fileChanges).isEmpty(),
                "null source must become an empty list, not propagate null into the map");
    }

    @Test
    void toPayload_shouldReturnMutableMapForHappyPath() {
        Map<String, Object> payload = CompactionPayloadMapper.toPayload(sampleResult());

        // Same mutability invariant as the null case - both branches must
        // return the same kind of Map, otherwise callers hit random UOEs.
        payload.put("probe", "ok");
        assertEquals("ok", payload.get("probe"));
    }

    @Test
    void toDetailsMap_shouldReturnMutableEmptyMapForNullDetails() {
        Map<String, Object> details = CompactionPayloadMapper.toDetailsMap(null);

        assertNotNull(details);
        assertTrue(details.isEmpty());
        details.put("probe", "ok");
        assertEquals("ok", details.get("probe"));
    }

    @Test
    void toPayload_shouldSerializeReasonAsEnumName() {
        CompactionResult result = sampleResult();

        Map<String, Object> payload = CompactionPayloadMapper.toPayload(result);

        assertEquals("AUTO_THRESHOLD", payload.get("reason"));
    }

    @Test
    void toPayload_shouldEmitPrimitiveAndCollectionValuesVerbatim() {
        CompactionResult result = sampleResult();

        Map<String, Object> payload = CompactionPayloadMapper.toPayload(result);

        assertEquals(5, payload.get("removed"));
        assertEquals(true, payload.get("usedSummary"));
        assertEquals(1, payload.get("schemaVersion"));
        assertEquals(3, payload.get("summarizedCount"));
        assertEquals(2, payload.get("keptCount"));
        assertEquals(true, payload.get("usedLlmSummary"));
        assertEquals(42, payload.get("summaryLength"));
        assertEquals(1, payload.get("toolCount"));
        assertEquals(1, payload.get("readFilesCount"));
        assertEquals(1, payload.get("modifiedFilesCount"));
        assertEquals(100L, payload.get("durationMs"));
        assertEquals(List.of("shell"), payload.get("toolNames"));
        assertEquals(List.of("src/Foo.java"), payload.get("readFiles"));
        assertEquals(List.of("src/Foo.java"), payload.get("modifiedFiles"));
        assertEquals(false, payload.get("splitTurnDetected"));
        assertEquals(false, payload.get("fallbackUsed"));
    }

    @Test
    void publishToContext_shouldMirrorCanonicalPayloadOntoContextAttribute() {
        AgentContext context = buildContext();
        CompactionResult result = sampleResult();

        CompactionPayloadMapper.publishToContext(context, result);

        Object stored = context.getAttribute(ContextAttributes.COMPACTION_LAST_DETAILS);
        assertInstanceOf(Map.class, stored);
        Map<?, ?> storedMap = (Map<?, ?>) stored;
        assertEquals(EXPECTED_PAYLOAD_KEYS, List.copyOf(storedMap.keySet()),
                "publishToContext must use the same canonical shape as toPayload - "
                        + "otherwise downstream systems that read from context see a different schema "
                        + "than dashboards that read from session metadata");
    }

    @Test
    void publishToContext_shouldBeNoOpWhenResultIsNull() {
        AgentContext context = buildContext();

        CompactionPayloadMapper.publishToContext(context, null);

        assertNull(context.getAttribute(ContextAttributes.COMPACTION_LAST_DETAILS));
    }

    @Test
    void publishToContext_shouldBeNoOpWhenDetailsAreNull() {
        AgentContext context = buildContext();
        CompactionResult resultWithoutDetails = CompactionResult.builder()
                .removed(0)
                .usedSummary(false)
                .details(null)
                .build();

        CompactionPayloadMapper.publishToContext(context, resultWithoutDetails);

        // Without details the payload is structurally incomplete; writing it
        // would put an invalid-shape map on the context where readers expect
        // either a full record or nothing.
        assertNull(context.getAttribute(ContextAttributes.COMPACTION_LAST_DETAILS));
    }

    @Test
    void publishToContext_shouldTolerateNullContext() {
        CompactionPayloadMapper.publishToContext(null, sampleResult());
        // No assertion beyond "did not throw" - the method must degrade
        // gracefully when a caller passes a half-constructed pipeline.
    }

    private AgentContext buildContext() {
        return AgentContext.builder()
                .session(AgentSession.builder().id("s1").chatId("c1").messages(new ArrayList<>()).build())
                .messages(new ArrayList<>())
                .build();
    }

    private CompactionResult sampleResult() {
        CompactionDetails details = CompactionDetails.builder()
                .schemaVersion(1)
                .reason(CompactionReason.AUTO_THRESHOLD)
                .summarizedCount(3)
                .keptCount(2)
                .usedLlmSummary(true)
                .summaryLength(42)
                .toolCount(1)
                .readFilesCount(1)
                .modifiedFilesCount(1)
                .durationMs(100L)
                .toolNames(List.of("shell"))
                .readFiles(List.of("src/Foo.java"))
                .modifiedFiles(List.of("src/Foo.java"))
                .splitTurnDetected(false)
                .fallbackUsed(false)
                .fileChanges(List.of(new CompactionDetails.FileChangeStat("src/Foo.java", 3, 1, false)))
                .build();
        return CompactionResult.builder()
                .removed(5)
                .usedSummary(true)
                .details(details)
                .build();
    }
}
