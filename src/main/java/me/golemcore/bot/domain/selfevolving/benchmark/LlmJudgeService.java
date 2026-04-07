package me.golemcore.bot.domain.selfevolving.benchmark;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.service.SessionService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Runs outcome and process judges on top of the existing LLM port and model
 * tier routing.
 */
@Service
@Slf4j
public class LlmJudgeService {

    private static final int MAX_CONVERSATION_SNIPPET_LENGTH = 2000;

    private static final String OUTCOME_SYSTEM_PROMPT = """
            You are the outcome judge for a golem SelfEvolving run.
            You receive the user's original request, the assistant's response, and structured trace data \
            including tool call sequences, error details, LLM call summaries, and span breakdowns.

            Your task:
            1. Assess whether the assistant's response actually fulfils the user's request.
            2. Score task_completion (0.0-1.0) and correctness based on the conversation and trace evidence.
            3. Recommend a rollout action: "approve_gated", "reject", or "observe".
            4. Anchor every claim to specific spanIds from the trace digest.

            Return only valid JSON matching the RunVerdict schema:
            {"outcomeStatus":"COMPLETED|FAILED|PARTIAL","outcomeSummary":"...","confidence":0.0-1.0,\
            "promotionRecommendation":"approve_gated|reject|observe",\
            "evidenceRefs":[{"traceId":"...","spanId":"...","outputFragment":"..."}],\
            "processFindings":[]}
            """;
    private static final String PROCESS_SYSTEM_PROMPT = """
            You are the process judge for a golem SelfEvolving run.
            You receive the user's original request, the assistant's response, and structured trace data \
            including tool call sequences, error details, LLM call summaries, and span breakdowns.

            Your task:
            1. Evaluate the agent's process: Was the tool sequence efficient? Were there unnecessary retries or churn?
            2. Check skill choice: Did the agent pick appropriate skills for the task?
            3. Check tier routing: Were LLM calls routed to appropriate model tiers for the task complexity?
            4. Identify process anti-patterns: tool loops, excessive errors, wasted LLM calls, timeout patterns.
            5. List each finding as a processFindings entry (e.g., "tool_churn:shell", "tier_overuse:deep").

            Return only valid JSON matching the RunVerdict schema:
            {"processStatus":"EFFICIENT|ISSUES_FOUND|INEFFICIENT","processSummary":"...","confidence":0.0-1.0,\
            "evidenceRefs":[{"traceId":"...","spanId":"...","outputFragment":"..."}],\
            "processFindings":["finding:detail"]}
            """;
    private static final String TIEBREAKER_SYSTEM_PROMPT = """
            You are the arbitration judge for a golem SelfEvolving run.
            You receive the same data as the prior judges, plus their merged verdict.

            Your task:
            1. Resolve disagreements between outcome and process judges.
            2. Re-examine the trace evidence where judges conflict.
            3. Produce a final verdict that is internally consistent.
            4. Keep evidenceRefs anchored to specific spanIds from the trace.

            Return only valid JSON matching the RunVerdict schema.
            """;

    private static final String JUDGE_CHANNEL_PREFIX = "judge_";

    private final LlmPort llmPort;
    private final JudgeTierResolver judgeTierResolver;
    private final JudgeTraceDigestService judgeTraceDigestService;
    private final RuntimeConfigService runtimeConfigService;
    private final SessionService sessionService;
    private final TraceService traceService;
    private final ObjectMapper objectMapper;

    public LlmJudgeService(
            LlmPort llmPort,
            JudgeTierResolver judgeTierResolver,
            JudgeTraceDigestService judgeTraceDigestService,
            RuntimeConfigService runtimeConfigService,
            SessionService sessionService,
            TraceService traceService) {
        this.llmPort = llmPort;
        this.judgeTierResolver = judgeTierResolver;
        this.judgeTraceDigestService = judgeTraceDigestService;
        this.runtimeConfigService = runtimeConfigService;
        this.sessionService = sessionService;
        this.traceService = traceService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    public RunVerdict judge(RunRecord runRecord, TraceRecord traceRecord, RunVerdict deterministicVerdict,
            String userQuery, String assistantResponse) {
        if (!isJudgeEnabled() || llmPort == null || !llmPort.isAvailable()) {
            return deterministicVerdict;
        }

        ModelSelectionService.ModelSelection primarySelection;
        try {
            primarySelection = judgeTierResolver.resolveSelection("primary");
        } catch (RuntimeException exception) {
            log.warn("[SelfEvolving] Failed to resolve judge tier: {}", exception.getMessage());
            return deterministicVerdict;
        }

        RunVerdict outcomeVerdict = requestJudgeVerdictSafe(
                "outcome", primarySelection, runRecord, traceRecord, deterministicVerdict,
                userQuery, assistantResponse);
        RunVerdict processVerdict = requestJudgeVerdictSafe(
                "process", primarySelection, runRecord, traceRecord, deterministicVerdict,
                userQuery, assistantResponse);

        RunVerdict mergedVerdict = mergeVerdicts(deterministicVerdict, outcomeVerdict, processVerdict);
        if (shouldEscalate(mergedVerdict)) {
            try {
                ModelSelectionService.ModelSelection tiebreakerSelection = judgeTierResolver.resolveSelection(
                        "tiebreaker");
                RunVerdict tiebreakerVerdict = requestJudgeVerdict(
                        "tiebreaker", tiebreakerSelection, runRecord, traceRecord, mergedVerdict,
                        userQuery, assistantResponse);
                mergedVerdict = applyOverride(mergedVerdict, tiebreakerVerdict);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException exception) {
                log.debug("[SelfEvolving] Tiebreaker judge failed, using merged verdict: {}", exception.getMessage());
            }
        }
        return mergedVerdict;
    }

    private RunVerdict requestJudgeVerdictSafe(
            String judgeType,
            ModelSelectionService.ModelSelection selection,
            RunRecord runRecord,
            TraceRecord traceRecord,
            RunVerdict seedVerdict,
            String userQuery,
            String assistantResponse) {
        try {
            return requestJudgeVerdict(judgeType, selection, runRecord, traceRecord, seedVerdict,
                    userQuery, assistantResponse);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("[SelfEvolving] {} judge interrupted", judgeType);
            return null;
        } catch (ExecutionException | RuntimeException exception) {
            log.warn("[SelfEvolving] {} judge failed: {}", judgeType, exception.getMessage());
            return null;
        }
    }

    public void validate(RunVerdict verdict) {
        if (verdict == null) {
            throw new IllegalArgumentException("Judge verdict must not be null");
        }
        if (requireEvidenceAnchors() && (verdict.getEvidenceRefs() == null || verdict.getEvidenceRefs().isEmpty())) {
            throw new IllegalArgumentException("Judge verdict must include evidence refs");
        }
    }

    private RunVerdict requestJudgeVerdict(
            String judgeType,
            ModelSelectionService.ModelSelection selection,
            RunRecord runRecord,
            TraceRecord traceRecord,
            RunVerdict seedVerdict,
            String userQuery,
            String assistantResponse) throws InterruptedException, ExecutionException {
        String runId = runRecord != null ? runRecord.getId() : "unknown";
        String channelType = JUDGE_CHANNEL_PREFIX + judgeType;
        String parentTraceId = runRecord != null ? runRecord.getTraceId() : null;

        AgentSession judgeSession = sessionService.getOrCreate(channelType, runId);
        Instant now = Instant.now();
        Map<String, Object> traceAttributes = new LinkedHashMap<>();
        traceAttributes.put("judge.type", judgeType);
        traceAttributes.put("judge.model", selection.model());
        traceAttributes.put("judge.run_id", runId);
        if (parentTraceId != null) {
            traceAttributes.put("judge.parent_trace_id", parentTraceId);
        }
        TraceContext rootTrace = traceService.startRootTrace(judgeSession,
                "judge:" + judgeType, TraceSpanKind.LLM, now, traceAttributes);

        log.debug("[SelfEvolving] Starting {} judge in session {} (parent trace: {})",
                judgeType, judgeSession.getId(), parentTraceId);

        String prompt = buildPrompt(judgeType, runRecord, traceRecord, seedVerdict, userQuery, assistantResponse);
        LlmRequest request = LlmRequest.builder()
                .model(selection.model())
                .reasoningEffort(selection.reasoning())
                .systemPrompt(resolveSystemPrompt(judgeType))
                .messages(List.of(Message.builder()
                        .role("user")
                        .content(prompt)
                        .build()))
                .temperature(0.1)
                .sessionId(judgeSession.getId())
                .traceId(rootTrace.getTraceId())
                .traceSpanId(rootTrace.getSpanId())
                .traceRootKind("judge_" + judgeType)
                .build();

        captureSnapshot(judgeSession, rootTrace, "request", request);
        try {
            LlmResponse response = llmPort.chat(request).get();
            captureSnapshot(judgeSession, rootTrace, "response", response);
            traceService.finishSpan(judgeSession, rootTrace, TraceStatusCode.OK, null, Instant.now());
            if (response == null || StringValueSupport.isBlank(response.getContent())) {
                throw new IllegalArgumentException("Judge returned empty response");
            }
            RunVerdict parsedVerdict = parseVerdict(response.getContent(), runRecord);
            validate(parsedVerdict);
            return parsedVerdict;
        } catch (InterruptedException | ExecutionException | RuntimeException exception) {
            traceService.finishSpan(judgeSession, rootTrace, TraceStatusCode.ERROR,
                    exception.getMessage(), Instant.now());
            throw exception;
        } finally {
            saveJudgeSession(judgeSession);
        }
    }

    private void captureSnapshot(AgentSession session, TraceContext spanContext, String role, Object payload) {
        try {
            RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);
            if (tracingConfig == null || !Boolean.TRUE.equals(tracingConfig.getEnabled())) {
                return;
            }
            byte[] data = objectMapper.writeValueAsBytes(payload);
            traceService.captureSnapshot(session, spanContext, tracingConfig,
                    role, "application/json", data);
        } catch (Exception exception) { // NOSONAR - tracing must not break judge flow
            log.debug("[SelfEvolving] Failed to capture {} snapshot: {}", role, exception.getMessage());
        }
    }

    private void saveJudgeSession(AgentSession session) {
        try {
            sessionService.save(session);
        } catch (RuntimeException exception) { // NOSONAR - persistence failure must not break judge
            log.warn("[SelfEvolving] Failed to save judge session {}: {}", session.getId(), exception.getMessage());
        }
    }

    private RunVerdict parseVerdict(String rawContent, RunRecord runRecord) {
        try {
            RunVerdict verdict = objectMapper.readValue(rawContent, RunVerdict.class);
            if (StringValueSupport.isBlank(verdict.getId())) {
                verdict.setId(UUID.randomUUID().toString());
            }
            if (StringValueSupport.isBlank(verdict.getRunId()) && runRecord != null) {
                verdict.setRunId(runRecord.getId());
            }
            if (verdict.getCreatedAt() == null) {
                verdict.setCreatedAt(Instant.now());
            }
            return verdict;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse judge verdict", e);
        }
    }

    private RunVerdict mergeVerdicts(RunVerdict deterministicVerdict, RunVerdict outcomeVerdict,
            RunVerdict processVerdict) {
        RunVerdict mergedVerdict = deterministicVerdict != null ? cloneVerdict(deterministicVerdict)
                : RunVerdict.builder()
                        .build();
        mergedVerdict = applyOverride(mergedVerdict, outcomeVerdict);
        mergedVerdict = applyOverride(mergedVerdict, processVerdict);
        mergedVerdict.setConfidence(resolveConfidence(outcomeVerdict, processVerdict, mergedVerdict));
        mergedVerdict.setEvidenceRefs(mergeEvidenceRefs(
                deterministicVerdict != null ? deterministicVerdict.getEvidenceRefs() : null,
                outcomeVerdict != null ? outcomeVerdict.getEvidenceRefs() : null,
                processVerdict != null ? processVerdict.getEvidenceRefs() : null));
        mergedVerdict.setProcessFindings(mergeProcessFindings(
                deterministicVerdict != null ? deterministicVerdict.getProcessFindings() : null,
                outcomeVerdict != null ? outcomeVerdict.getProcessFindings() : null,
                processVerdict != null ? processVerdict.getProcessFindings() : null));
        return mergedVerdict;
    }

    private RunVerdict applyOverride(RunVerdict baseVerdict, RunVerdict overrideVerdict) {
        if (overrideVerdict == null) {
            return baseVerdict;
        }
        RunVerdict mergedVerdict = cloneVerdict(baseVerdict);
        if (!StringValueSupport.isBlank(overrideVerdict.getOutcomeStatus())) {
            mergedVerdict.setOutcomeStatus(overrideVerdict.getOutcomeStatus());
        }
        if (!StringValueSupport.isBlank(overrideVerdict.getProcessStatus())) {
            mergedVerdict.setProcessStatus(overrideVerdict.getProcessStatus());
        }
        if (!StringValueSupport.isBlank(overrideVerdict.getOutcomeSummary())) {
            mergedVerdict.setOutcomeSummary(overrideVerdict.getOutcomeSummary());
        }
        if (!StringValueSupport.isBlank(overrideVerdict.getProcessSummary())) {
            mergedVerdict.setProcessSummary(overrideVerdict.getProcessSummary());
        }
        if (!StringValueSupport.isBlank(overrideVerdict.getPromotionRecommendation())) {
            mergedVerdict.setPromotionRecommendation(overrideVerdict.getPromotionRecommendation());
        }
        if (overrideVerdict.getConfidence() != null) {
            mergedVerdict.setConfidence(overrideVerdict.getConfidence());
        }
        if (!StringValueSupport.isBlank(overrideVerdict.getUncertaintyReason())) {
            mergedVerdict.setUncertaintyReason(overrideVerdict.getUncertaintyReason());
        }
        if (overrideVerdict.getDimensionScores() != null && !overrideVerdict.getDimensionScores().isEmpty()) {
            mergedVerdict.setDimensionScores(new ArrayList<>(overrideVerdict.getDimensionScores()));
        }
        if (overrideVerdict.getEvidenceRefs() != null && !overrideVerdict.getEvidenceRefs().isEmpty()) {
            mergedVerdict.setEvidenceRefs(
                    mergeEvidenceRefs(mergedVerdict.getEvidenceRefs(), overrideVerdict.getEvidenceRefs()));
        }
        if (overrideVerdict.getProcessFindings() != null && !overrideVerdict.getProcessFindings().isEmpty()) {
            mergedVerdict.setProcessFindings(
                    mergeProcessFindings(mergedVerdict.getProcessFindings(), overrideVerdict.getProcessFindings()));
        }
        return mergedVerdict;
    }

    private RunVerdict cloneVerdict(RunVerdict sourceVerdict) {
        if (sourceVerdict == null) {
            return RunVerdict.builder().build();
        }
        return RunVerdict.builder()
                .id(sourceVerdict.getId())
                .runId(sourceVerdict.getRunId())
                .baselineVerdictId(sourceVerdict.getBaselineVerdictId())
                .outcomeStatus(sourceVerdict.getOutcomeStatus())
                .processStatus(sourceVerdict.getProcessStatus())
                .outcomeSummary(sourceVerdict.getOutcomeSummary())
                .processSummary(sourceVerdict.getProcessSummary())
                .confidence(sourceVerdict.getConfidence())
                .uncertaintyReason(sourceVerdict.getUncertaintyReason())
                .promotionRecommendation(sourceVerdict.getPromotionRecommendation())
                .createdAt(sourceVerdict.getCreatedAt())
                .dimensionScores(sourceVerdict.getDimensionScores() != null
                        ? new ArrayList<>(sourceVerdict.getDimensionScores())
                        : new ArrayList<>())
                .evidenceRefs(sourceVerdict.getEvidenceRefs() != null
                        ? new ArrayList<>(sourceVerdict.getEvidenceRefs())
                        : new ArrayList<>())
                .processFindings(sourceVerdict.getProcessFindings() != null
                        ? new ArrayList<>(sourceVerdict.getProcessFindings())
                        : new ArrayList<>())
                .build();
    }

    private Double resolveConfidence(RunVerdict outcomeVerdict, RunVerdict processVerdict, RunVerdict mergedVerdict) {
        Double outcomeConfidence = outcomeVerdict != null ? outcomeVerdict.getConfidence() : null;
        Double processConfidence = processVerdict != null ? processVerdict.getConfidence() : null;
        if (outcomeConfidence != null && processConfidence != null) {
            return Math.min(outcomeConfidence, processConfidence);
        }
        if (processConfidence != null) {
            return processConfidence;
        }
        if (outcomeConfidence != null) {
            return outcomeConfidence;
        }
        return mergedVerdict.getConfidence();
    }

    private List<VerdictEvidenceRef> mergeEvidenceRefs(List<VerdictEvidenceRef>... groups) {
        List<VerdictEvidenceRef> mergedRefs = new ArrayList<>();
        for (List<VerdictEvidenceRef> group : groups) {
            if (group == null) {
                continue;
            }
            for (VerdictEvidenceRef evidenceRef : group) {
                if (evidenceRef == null || containsEvidenceRef(mergedRefs, evidenceRef)) {
                    continue;
                }
                mergedRefs.add(evidenceRef);
            }
        }
        return mergedRefs;
    }

    private boolean containsEvidenceRef(List<VerdictEvidenceRef> refs, VerdictEvidenceRef candidateRef) {
        return refs.stream().anyMatch(existingRef -> Objects.equals(existingRef.getTraceId(), candidateRef.getTraceId())
                && Objects.equals(existingRef.getSpanId(), candidateRef.getSpanId())
                && Objects.equals(existingRef.getSnapshotId(), candidateRef.getSnapshotId())
                && Objects.equals(existingRef.getMetricName(), candidateRef.getMetricName()));
    }

    private List<String> mergeProcessFindings(List<String>... groups) {
        List<String> mergedFindings = new ArrayList<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String finding : group) {
                if (StringValueSupport.isBlank(finding) || mergedFindings.contains(finding)) {
                    continue;
                }
                mergedFindings.add(finding);
            }
        }
        return mergedFindings;
    }

    private boolean shouldEscalate(RunVerdict mergedVerdict) {
        RuntimeConfig.SelfEvolvingConfig config = runtimeConfigService.getSelfEvolvingConfig();
        Double threshold = config != null && config.getJudge() != null ? config.getJudge().getUncertaintyThreshold()
                : null;
        return mergedVerdict != null
                && mergedVerdict.getConfidence() != null
                && threshold != null
                && mergedVerdict.getConfidence() < threshold;
    }

    private boolean isJudgeEnabled() {
        RuntimeConfig.SelfEvolvingConfig config = runtimeConfigService.getSelfEvolvingConfig();
        return config != null && config.getJudge() != null && Boolean.TRUE.equals(config.getJudge().getEnabled());
    }

    private boolean requireEvidenceAnchors() {
        RuntimeConfig.SelfEvolvingConfig config = runtimeConfigService.getSelfEvolvingConfig();
        return config == null
                || config.getJudge() == null
                || Boolean.TRUE.equals(config.getJudge().getRequireEvidenceAnchors());
    }

    private String resolveSystemPrompt(String judgeType) {
        return switch (judgeType) {
        case "outcome" -> OUTCOME_SYSTEM_PROMPT;
        case "process" -> PROCESS_SYSTEM_PROMPT;
        case "tiebreaker" -> TIEBREAKER_SYSTEM_PROMPT;
        default -> OUTCOME_SYSTEM_PROMPT;
        };
    }

    private String buildPrompt(String judgeType, RunRecord runRecord, TraceRecord traceRecord,
            RunVerdict seedVerdict, String userQuery, String assistantResponse) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("judgeType=").append(judgeType).append('\n');
        if (runRecord != null) {
            promptBuilder.append("runId=").append(runRecord.getId()).append('\n');
            promptBuilder.append("runStatus=").append(runRecord.getStatus()).append('\n');
            promptBuilder.append("golemId=").append(runRecord.getGolemId()).append('\n');
        }
        if (traceRecord != null) {
            promptBuilder.append("traceId=").append(traceRecord.getTraceId()).append('\n');
        }
        if (seedVerdict != null) {
            promptBuilder.append("seedOutcomeStatus=").append(seedVerdict.getOutcomeStatus()).append('\n');
            promptBuilder.append("seedProcessStatus=").append(seedVerdict.getProcessStatus()).append('\n');
            promptBuilder.append("seedProcessFindings=").append(seedVerdict.getProcessFindings()).append('\n');
        }

        if (!StringValueSupport.isBlank(userQuery)) {
            promptBuilder.append('\n');
            promptBuilder.append("=== USER REQUEST ===\n");
            promptBuilder.append(truncateSnippet(userQuery)).append('\n');
            promptBuilder.append("=== END USER REQUEST ===\n");
        }
        if (!StringValueSupport.isBlank(assistantResponse)) {
            promptBuilder.append('\n');
            promptBuilder.append("=== ASSISTANT RESPONSE ===\n");
            promptBuilder.append(truncateSnippet(assistantResponse)).append('\n');
            promptBuilder.append("=== END ASSISTANT RESPONSE ===\n");
        }

        String traceDigest = judgeTraceDigestService.buildDigest(runRecord, traceRecord);
        if (!StringValueSupport.isBlank(traceDigest)) {
            promptBuilder.append('\n');
            promptBuilder.append("=== TRACE DIGEST ===\n");
            promptBuilder.append(traceDigest);
            promptBuilder.append("=== END TRACE DIGEST ===\n");
        }

        promptBuilder.append('\n');
        promptBuilder.append("Return strict JSON only.");
        return promptBuilder.toString();
    }

    private String truncateSnippet(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_CONVERSATION_SNIPPET_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONVERSATION_SNIPPET_LENGTH) + "\n... [truncated]";
    }
}
