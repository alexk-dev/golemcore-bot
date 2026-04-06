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

import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.VerdictDimensionScore;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Computes deterministic run verdicts before any LLM-based judging.
 */
@Service
public class DeterministicJudgeService {

    public RunVerdict evaluate(RunRecord runRecord, TraceRecord traceRecord) {
        List<String> processFindings = new ArrayList<>();
        List<VerdictEvidenceRef> evidenceRefs = new ArrayList<>();

        if (traceRecord != null && traceRecord.getSpans() != null) {
            for (TraceSpanRecord span : traceRecord.getSpans()) {
                if (span == null || span.getStatusCode() != TraceStatusCode.ERROR) {
                    continue;
                }
                String finding = span.getKind() == TraceSpanKind.TOOL
                        ? "tool_error:" + defaultIfBlank(span.getName(), span.getSpanId())
                        : "span_error:" + defaultIfBlank(span.getName(), span.getSpanId());
                processFindings.add(finding);
                evidenceRefs.add(VerdictEvidenceRef.builder()
                        .traceId(traceRecord.getTraceId())
                        .spanId(span.getSpanId())
                        .outputFragment(span.getStatusMessage())
                        .build());
            }
        }

        if (traceRecord != null && traceRecord.isTruncated()) {
            processFindings.add("trace_truncated");
            evidenceRefs.add(VerdictEvidenceRef.builder()
                    .traceId(traceRecord.getTraceId())
                    .metricName("trace.truncated")
                    .metricValue(1.0)
                    .build());
        }

        String outcomeStatus = resolveOutcomeStatus(runRecord, traceRecord);
        String processStatus = processFindings.isEmpty() ? "CLEAN" : "ISSUES_FOUND";
        double completionScore = "FAILED".equals(outcomeStatus) ? 0.0 : 1.0;
        double processHealthScore = processFindings.isEmpty()
                ? 1.0
                : Math.max(0.0, 1.0 - (processFindings.size() * 0.25));

        return RunVerdict.builder()
                .id(buildVerdictId(runRecord))
                .runId(runRecord != null ? runRecord.getId() : null)
                .outcomeStatus(outcomeStatus)
                .processStatus(processStatus)
                .outcomeSummary(buildOutcomeSummary(outcomeStatus))
                .processSummary(buildProcessSummary(processFindings))
                .confidence(1.0)
                .promotionRecommendation(resolvePromotionRecommendation(outcomeStatus))
                .createdAt(Instant.now())
                .dimensionScores(List.of(
                        VerdictDimensionScore.builder()
                                .dimension("task_completion")
                                .score(completionScore)
                                .rationale(buildOutcomeSummary(outcomeStatus))
                                .build(),
                        VerdictDimensionScore.builder()
                                .dimension("process_health")
                                .score(processHealthScore)
                                .rationale(buildProcessSummary(processFindings))
                                .build()))
                .evidenceRefs(evidenceRefs)
                .processFindings(processFindings)
                .build();
    }

    private String resolveOutcomeStatus(RunRecord runRecord, TraceRecord traceRecord) {
        if (runRecord != null && "FAILED".equalsIgnoreCase(runRecord.getStatus())) {
            return "FAILED";
        }
        if (traceRecord != null && traceRecord.getSpans() != null) {
            boolean hasErrorSpan = traceRecord.getSpans().stream()
                    .filter(span -> span != null)
                    .anyMatch(span -> span.getStatusCode() == TraceStatusCode.ERROR);
            if (hasErrorSpan) {
                return "FAILED";
            }
        }
        if (runRecord != null && !StringValueSupport.isBlank(runRecord.getStatus())) {
            return runRecord.getStatus().toUpperCase();
        }
        return "COMPLETED";
    }

    private String resolvePromotionRecommendation(String outcomeStatus) {
        return "FAILED".equals(outcomeStatus) ? "reject" : "approve_gated";
    }

    private String buildVerdictId(RunRecord runRecord) {
        if (runRecord == null || StringValueSupport.isBlank(runRecord.getId())) {
            return UUID.randomUUID().toString();
        }
        return runRecord.getId() + "-deterministic";
    }

    private String buildOutcomeSummary(String outcomeStatus) {
        if ("FAILED".equals(outcomeStatus)) {
            return "Run failed deterministic checks";
        }
        if ("RUNNING".equals(outcomeStatus)) {
            return "Run is still in progress";
        }
        return "Run completed deterministic checks";
    }

    private String buildProcessSummary(List<String> processFindings) {
        return processFindings.isEmpty()
                ? "No deterministic process issues detected"
                : String.join(", ", processFindings);
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringValueSupport.isBlank(value) ? fallback : value;
    }
}
