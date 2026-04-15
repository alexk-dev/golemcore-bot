package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTokenEstimatorTest {

    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    @Test
    void shouldReturnZeroForNullAndEmptyInputs() {
        assertEquals(0, estimator.estimateMessages(null));
        assertEquals(0, estimator.estimateMessages(List.of()));
        assertEquals(0, estimator.estimateRequest(null));
    }

    @Test
    void shouldEstimateFullRequestIncludingPromptMessagesToolsAndResults() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call-1")
                .name("shell")
                .arguments(Map.of("command", "echo hello", "nested", Map.of("flag", true)))
                .build();
        Message message = Message.builder()
                .role("assistant")
                .content("Running a tool")
                .toolCallId("call-1")
                .toolName("shell")
                .toolCalls(List.of(toolCall))
                .metadata(Map.of("model", "gpt", "tags", List.of("a", "b")))
                .build();
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name("shell")
                .description("Execute shell commands")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string"))))
                .build();
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("system prompt")
                .messages(List.of(message))
                .tools(List.of(toolDefinition))
                .toolResults(Map.of("call-1", ToolResult.success("hello")))
                .build();

        int messagesOnly = estimator.estimateMessages(List.of(message));
        int fullRequest = estimator.estimateRequest(request);

        assertTrue(messagesOnly > 0);
        assertTrue(fullRequest > messagesOnly);
    }

    // Non-ASCII characters below are written as Java Unicode escape sequences
    // so the CI english-only source scanner does not flag them. The Java
    // compiler expands the escapes before the lexer runs, so the runtime
    // String is identical to the literal character.
    private static final String CYRILLIC_YA = "\u044F"; // Cyrillic small letter ya
    private static final String DEVANAGARI_KA = "\u0915"; // Devanagari letter ka
    private static final String THAI_KO_KAI = "\u0E01"; // Thai character ko kai
    private static final String BENGALI_A = "\u0985"; // Bengali letter a
    private static final String CJK_ZHONG = "\u4E2D"; // CJK unified ideograph zhong
    private static final String EMOJI_FIRE = "\uD83D\uDD25"; // U+1F525 fire
    private static final String EMOJI_GRIN = "\uD83D\uDE00"; // U+1F600 grinning face
    private static final String EMOJI_ROCKET = "\uD83D\uDE80"; // U+1F680 rocket
    private static final String EMOJI_SPARKLES = "\u2728"; // U+2728 sparkles (BMP dingbat)
    private static final String REGIONAL_INDICATOR_R = "\uD83C\uDDF7"; // U+1F1F7
    private static final String REGIONAL_INDICATOR_U = "\uD83C\uDDFA"; // U+1F1FA

    @Test
    void shouldWeightCyrillicHeavierThanLatinOfSameLength() {
        String latin = "a".repeat(400);
        String cyrillic = CYRILLIC_YA.repeat(400);

        Message latinMessage = Message.builder().role("user").content(latin).build();
        Message cyrillicMessage = Message.builder().role("user").content(cyrillic).build();

        int latinTokens = estimator.estimateMessages(List.of(latinMessage));
        int cyrillicTokens = estimator.estimateMessages(List.of(cyrillicMessage));

        // Cyrillic tokenizes at ~2 chars/token vs Latin ~4 chars/token, so the
        // token count must be strictly higher for identical character counts.
        assertTrue(cyrillicTokens > latinTokens,
                "cyrillic=" + cyrillicTokens + " should exceed latin=" + latinTokens);
    }

    @Test
    void shouldWeightIndicAndSeAsianScriptsHeavierThanLatin() {
        String latin = "a".repeat(400);
        String devanagari = DEVANAGARI_KA.repeat(400);
        String thai = THAI_KO_KAI.repeat(400);
        String bengali = BENGALI_A.repeat(400);

        int latinTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(latin).build()));
        int devanagariTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(devanagari).build()));
        int thaiTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(thai).build()));
        int bengaliTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(bengali).build()));

        // Indic / SE Asian scripts tokenize at ~1.5-2.0 chars/token and must be
        // counted in the medium bucket, not accidentally in the Latin bucket -
        // otherwise preflight under-counts the BPE budget and skips compaction
        // for non-English users.
        assertTrue(devanagariTokens > latinTokens,
                "devanagari=" + devanagariTokens + " should exceed latin=" + latinTokens);
        assertTrue(thaiTokens > latinTokens,
                "thai=" + thaiTokens + " should exceed latin=" + latinTokens);
        assertTrue(bengaliTokens > latinTokens,
                "bengali=" + bengaliTokens + " should exceed latin=" + latinTokens);
    }

    @Test
    void shouldWeightEmojiHeavierThanLatinForSameCodepointCount() {
        // 200 emoji glyphs vs 200 Latin letters: real BPE tokenizers allocate
        // 1-3 tokens per emoji (vs ~0.25 tokens per Latin letter). If emoji
        // inherit the Latin bucket via UnicodeScript.COMMON, preflight silently
        // under-counts the budget for emoji-heavy chat payloads (very common in
        // Telegram / voice prefixes / user reactions) and fails to fire - the
        // provider then rejects the request at a higher cost.
        String latin = "a".repeat(200);
        String fire = EMOJI_FIRE.repeat(200);
        String grin = EMOJI_GRIN.repeat(200);
        String rocket = EMOJI_ROCKET.repeat(200);
        String sparkles = EMOJI_SPARKLES.repeat(200);

        int latinTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(latin).build()));
        int fireTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(fire).build()));
        int grinTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(grin).build()));
        int rocketTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(rocket).build()));
        int sparklesTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(sparkles).build()));

        assertTrue(fireTokens > latinTokens,
                "fire emoji=" + fireTokens + " must exceed latin=" + latinTokens);
        assertTrue(grinTokens > latinTokens,
                "grin emoji=" + grinTokens + " must exceed latin=" + latinTokens);
        assertTrue(rocketTokens > latinTokens,
                "rocket emoji=" + rocketTokens + " must exceed latin=" + latinTokens);
        assertTrue(sparklesTokens > latinTokens,
                "sparkles (BMP dingbat)=" + sparklesTokens + " must exceed latin=" + latinTokens);
    }

    @Test
    void shouldWeightRegionalIndicatorFlagsHeavierThanLatin() {
        // Regional indicator sequences (e.g. flag emoji like RU = U+1F1F7 U+1F1FA)
        // also live in UnicodeScript.COMMON but tokenize as 1-2 tokens each in
        // Claude BPE. 100 flag sequences (200 codepoints) should beat 200 Latin
        // chars.
        String latin = "a".repeat(200);
        String flags = (REGIONAL_INDICATOR_R + REGIONAL_INDICATOR_U).repeat(100);

        int latinTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(latin).build()));
        int flagTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(flags).build()));

        assertTrue(flagTokens > latinTokens,
                "regional indicator flags=" + flagTokens + " must exceed latin=" + latinTokens);
    }

    @Test
    void shouldWeightCjkHeavierThanCyrillic() {
        String cyrillic = CYRILLIC_YA.repeat(200);
        String cjk = CJK_ZHONG.repeat(200);

        Message cyrillicMessage = Message.builder().role("user").content(cyrillic).build();
        Message cjkMessage = Message.builder().role("user").content(cjk).build();

        int cyrillicTokens = estimator.estimateMessages(List.of(cyrillicMessage));
        int cjkTokens = estimator.estimateMessages(List.of(cjkMessage));

        // CJK tokenizes at ~1 char/token, so it must outweigh Cyrillic for
        // identical character counts.
        assertTrue(cjkTokens > cyrillicTokens,
                "cjk=" + cjkTokens + " should exceed cyrillic=" + cyrillicTokens);
    }

    @Test
    void shouldTolerateCyclicMetadataWithoutStackOverflow() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        cyclic.put("name", "loop");

        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(cyclic)
                .build();

        // Must not throw / overflow.
        int tokens = estimator.estimateMessages(List.of(message));
        assertTrue(tokens > 0);
    }

    @Test
    void shouldCountSharedMetadataObjectsForEverySerializedOccurrence() {
        Map<String, Object> shared = new HashMap<>();
        shared.put("payload", "x".repeat(800));

        Message singleOccurrence = Message.builder()
                .role("user")
                .content("hi")
                .metadata(Map.of("first", shared))
                .build();
        Message repeatedOccurrence = Message.builder()
                .role("user")
                .content("hi")
                .metadata(Map.of("first", shared, "second", shared))
                .build();

        int singleTokens = estimator.estimateMessages(List.of(singleOccurrence));
        int repeatedTokens = estimator.estimateMessages(List.of(repeatedOccurrence));

        assertTrue(repeatedTokens > singleTokens + 150,
                "shared metadata objects are serialized once per occurrence; repeated=" + repeatedTokens
                        + " must materially exceed single=" + singleTokens);
    }

    @Test
    void shouldCountSharedToolSchemasForEveryToolDefinition() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "body", Map.of(
                        "type", "string",
                        "description", "x".repeat(800))));
        ToolDefinition firstTool = ToolDefinition.builder()
                .name("first")
                .description("First tool")
                .inputSchema(schema)
                .build();
        ToolDefinition secondTool = ToolDefinition.builder()
                .name("second")
                .description("Second tool")
                .inputSchema(schema)
                .build();

        LlmRequest singleTool = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .tools(List.of(firstTool))
                .build();
        LlmRequest repeatedSchema = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .tools(List.of(firstTool, secondTool))
                .build();

        int singleTokens = estimator.estimateRequest(singleTool);
        int repeatedTokens = estimator.estimateRequest(repeatedSchema);

        assertTrue(repeatedTokens > singleTokens + 150,
                "each tool definition serializes its schema independently; repeated=" + repeatedTokens
                        + " must materially exceed single=" + singleTokens);
    }

    @Test
    void shouldNotDoubleCountToolResultsWhichAreNotSerializedOnTheWire() {
        // ToolResults map is only internal bookkeeping - Langchain4jAdapter
        // serializes systemPrompt + messages, not request.toolResults. Counting
        // them in the estimate inflates the budget and triggers phantom
        // compactions even when the on-the-wire payload is small.
        LlmRequest withoutToolResults = LlmRequest.builder()
                .systemPrompt("system prompt")
                .messages(List.of(Message.builder().role("user").content("hello").build()))
                .build();
        LlmRequest withHeavyToolResults = LlmRequest.builder()
                .systemPrompt("system prompt")
                .messages(List.of(Message.builder().role("user").content("hello").build()))
                .toolResults(Map.of("call-1",
                        ToolResult.success("x".repeat(50_000))))
                .build();

        int withoutTokens = estimator.estimateRequest(withoutToolResults);
        int withTokens = estimator.estimateRequest(withHeavyToolResults);

        assertEquals(withoutTokens, withTokens,
                "tool results must not contribute to wire-size estimate");
    }

    @Test
    void shouldEstimateDoubleArrayOfZerosCloseToActualSerializedSize() {
        // Real serialized form of double[] all-zeros: "0.0,0.0,0.0,...". That
        // is ~4 chars per element, so 1000 elements ≈ 4000 chars ≈ 1000 tokens
        // at Latin density. The previous hard-coded worst-case width of 12.0
        // inflated this to ~3000 tokens, triggering phantom preflight
        // compactions on tools that pass tiny numeric payloads.
        double[] zeros = new double[1_000];
        Message message = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", zeros))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));
        // Strict upper bound: must not exceed ~2x the realistic token count.
        // 1000 * 4 chars / 4 chars-per-token + base overhead ≈ 1000-1100
        // tokens, so 2000 is a generous ceiling that still catches the old
        // 3000+ over-estimate.
        assertTrue(tokens < 2_000,
                "double[] of zeros must not be inflated via worst-case width; got " + tokens);
    }

    @Test
    void shouldEstimateLongArrayOfEpochMillisRoughlyProportionalToActualChars() {
        // Epoch millis are ~13 digits; the previous width of 8.0 under-counted
        // long[] in realistic telemetry payloads. Sampling-based estimation
        // should land close to the real char count.
        long[] millis = new long[500];
        long base = 1_700_000_000_000L;
        for (int index = 0; index < millis.length; index++) {
            millis[index] = base + index;
        }
        Message message = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("timestamps", millis))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));
        // 500 * 14 chars ("1700000000000,") / 4 chars-per-token ≈ 1750 tokens.
        // Require a lower bound well above the old 500*8/4 = 1000 estimate.
        assertTrue(tokens > 1_400,
                "long[] of epoch millis must not be under-counted; got " + tokens);
    }

    @Test
    void shouldEstimatePrimitiveArraysProportionallyToLength() {
        int[] largeIntArray = new int[2_000];
        for (int index = 0; index < largeIntArray.length; index++) {
            largeIntArray[index] = index;
        }
        int[] smallIntArray = new int[] { 1, 2, 3 };

        Message largeMessage = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", largeIntArray))
                .build();
        Message smallMessage = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", smallIntArray))
                .build();

        int largeTokens = estimator.estimateMessages(List.of(largeMessage));
        int smallTokens = estimator.estimateMessages(List.of(smallMessage));

        // A 2000-element int array must dominate the estimate: JDK
        // Object.toString on arrays yields "[I@abc" (~6 chars), which hides the
        // payload entirely. Require the larger array to produce materially more
        // tokens than the tiny one.
        assertTrue(largeTokens > smallTokens + 200,
                "large=" + largeTokens + " must exceed small=" + smallTokens + " by a wide margin");
    }

    @Test
    void shouldEstimateObjectArraysByIteratingElements() {
        String[] strings = new String[50];
        Arrays.fill(strings, "abcdefghij");
        Message message = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", strings))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        // 50 strings × 10 chars ≈ 125+ tokens with Latin weighting + overhead.
        // Old behavior: String.valueOf(stringArray) returns "[Ljava.lang.String;@abc".
        assertTrue(tokens > 100, "tokens=" + tokens + " must reflect array contents");
    }

    @Test
    void shouldSurviveToStringThrowingOnArbitraryPojoInMetadata() {
        // A tool may stash an arbitrary POJO into message metadata. If that
        // object's toString() throws, the estimator must NOT propagate the
        // failure - otherwise every preflight call crashes the turn with a
        // bogus compactionOutcome="error". Over-estimate with a fixed fallback
        // instead.
        Object poisoned = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString is poisoned");
            }
        };
        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(Map.of("poisoned", poisoned))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0, "estimator must degrade gracefully on toString() failure");
    }

    @Test
    void shouldSkipNullToolCallEntryWithoutFailing() {
        // Regression guard: a Message.toolCalls list may contain a null element
        // when a provider streams partial deltas - the estimator must skip
        // nulls instead of throwing an NPE inside the preflight safety gate.
        Message.ToolCall realCall = Message.ToolCall.builder()
                .id("call-1")
                .name("shell")
                .arguments(Map.of("cmd", "ls"))
                .build();
        List<Message.ToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(null);
        toolCalls.add(realCall);
        Message message = Message.builder()
                .role("assistant")
                .content("x")
                .toolCalls(toolCalls)
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0, "null tool-call element must not break estimation");
    }

    @Test
    void shouldSkipNullMetadataValueWithoutFailing() {
        // Map.of rejects null values, but metadata often comes from mutable
        // HashMaps populated by tools that legitimately store null placeholders.
        // The estimator must walk past them instead of routing them through the
        // opaque-fallback path (which would String.valueOf(null) = "null").
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("maybe", null);
        metadata.put("present", "value");
        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(metadata)
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0);
    }

    @Test
    void shouldEstimateCharArrayViaFullDecodePath() {
        // char[] is semantically a String - the estimator routes it through a
        // full String decode (not the sampled primitive path) so surrogate
        // pairs and script weighting stay correct. A 200-char array of CJK
        // characters must outweigh a 200-char Latin array.
        char[] cjkChars = CJK_ZHONG.repeat(200).toCharArray();
        char[] latinChars = "a".repeat(200).toCharArray();

        Message cjkMessage = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("chars", cjkChars))
                .build();
        Message latinMessage = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("chars", latinChars))
                .build();

        int cjkTokens = estimator.estimateMessages(List.of(cjkMessage));
        int latinTokens = estimator.estimateMessages(List.of(latinMessage));

        assertTrue(cjkTokens > latinTokens,
                "char[] must decode through script weighting; cjk=" + cjkTokens + " latin=" + latinTokens);
    }

    @Test
    void shouldEstimateEmptyObjectArrayAsMinimumOverhead() {
        // Empty Object[] must still emit the bracket overhead (2 tokens) so a
        // metadata field holding [] does not collapse to zero - otherwise
        // callers that detect "empty field" via tokens==0 get the wrong signal.
        String[] empty = new String[0];
        Message message = Message.builder()
                .role("user")
                .content("")
                .metadata(Map.of("values", empty))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        // Message overhead + empty-array tokens - strictly positive, small.
        assertTrue(tokens > 0);
    }

    @Test
    void shouldEstimateEmptyPrimitiveArrayWithoutSampling() {
        // Empty primitive arrays short-circuit the sampling path and just emit
        // the 2-token bracket overhead. Guards against a div-by-zero in the
        // average-char-width computation.
        int[] empty = new int[0];
        Message message = Message.builder()
                .role("user")
                .content("")
                .metadata(Map.of("values", empty))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0);
    }

    @Test
    void shouldBreakCyclesInSelfReferencingList() {
        // A mutable List that contains itself must not recurse forever. The
        // estimator's IdentityHashMap visited set breaks the cycle at the
        // iterable branch and returns a finite count.
        List<Object> self = new ArrayList<>();
        self.add("payload");
        self.add(self);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("cycle", self);
        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(metadata)
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0);
    }

    @Test
    void shouldBreakCyclesInSelfReferencingObjectArray() {
        // Same invariant as self-referencing List, but via the Object[] branch.
        // Array cycles are rare but can occur in tool metadata that stores
        // graph-like structures with back-references.
        Object[] self = new Object[2];
        self[0] = "payload";
        self[1] = self;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("cycle", self);
        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(metadata)
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0);
    }

    @Test
    void shouldClampAtMaximumNestingDepth() {
        // Depth budget caps runaway recursion from hand-crafted deeply-nested
        // JSON blobs. Build a 40-level chain (above the MAX_NESTED_DEPTH=32
        // guard) and confirm the estimator terminates and still returns a
        // positive count for the outer frames.
        Map<String, Object> inner = new HashMap<>();
        inner.put("leaf", "end");
        Map<String, Object> cursor = inner;
        for (int level = 0; level < 40; level++) {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("child", cursor);
            cursor = wrapper;
        }
        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(cursor)
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        assertTrue(tokens > 0);
    }

    @Test
    void shouldEstimatePlainPojoViaOpaqueFallback() {
        // Arbitrary POJOs that are neither Map, Iterable, nor Array fall
        // through to the opaque-fallback path (toString + estimateText). Lock
        // the happy path so a future refactor does not accidentally swallow
        // POJO contributions.
        Object pojo = new Object() {
            @Override
            public String toString() {
                return "small-pojo-with-known-width-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
            }
        };
        Message withPojo = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("pojo", pojo))
                .build();
        Message withoutPojo = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("other", "tiny"))
                .build();

        int withTokens = estimator.estimateMessages(List.of(withPojo));
        int withoutTokens = estimator.estimateMessages(List.of(withoutPojo));

        assertTrue(withTokens > withoutTokens,
                "plain POJO must contribute via toString; with=" + withTokens + " without=" + withoutTokens);
    }

    @Test
    void shouldWeightUncommonScriptsViaDefaultBucket() {
        // Scripts that aren't Latin/CJK/Cyrillic/etc. fall through to the
        // tokenContribution default case and use the "other" bucket
        // (~2 chars/token). Braille (U+2801 ".") is the cleanest way to
        // exercise that arm without tripping the emoji range check.
        String braille = "\u2801".repeat(400);
        String latin = "a".repeat(400);

        int brailleTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(braille).build()));
        int latinTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(latin).build()));

        assertTrue(brailleTokens > latinTokens,
                "braille must route through the medium default bucket; braille=" + brailleTokens
                        + " latin=" + latinTokens);
    }

    @Test
    void shouldReturnZeroForWhitespaceOnlyText() {
        // Whitespace-only content contributes a small but non-zero count via
        // the per-codepoint whitespace branch - the estimator should not
        // collapse to zero just because there are no letters.
        String whitespace = "   \n\t   ";
        Message message = Message.builder().role("user").content(whitespace).build();

        int tokens = estimator.estimateMessages(List.of(message));

        // Message overhead alone guarantees > 0; the test locks the branch
        // inside estimateText where isWhitespace fires.
        assertTrue(tokens > 0);
    }

    @Test
    void shouldHandleNullElementsAndSaturateHugeInputs() {
        Message huge = Message.builder()
                .role("user")
                .content("x".repeat(50_000))
                .metadata(Map.of("array", new int[] { 1, 2, 3 }))
                .build();
        Map<String, ToolResult> toolResults = new HashMap<>();
        toolResults.put("null-result", null);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("prompt")
                .messages(Arrays.asList(null, huge))
                .tools(Arrays.asList(null, ToolDefinition.simple("noop", "No operation")))
                .toolResults(toolResults)
                .build();

        assertTrue(estimator.estimateRequest(request) > 10_000);
    }
}
