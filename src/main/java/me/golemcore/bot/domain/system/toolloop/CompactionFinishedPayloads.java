package me.golemcore.bot.domain.system.toolloop;

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

import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure payload builders for every COMPACTION_FINISHED runtime event variant
 * (success, no-change, error). Extracted from {@link LlmRequestPreflightPhase}
 * so the preflight phase stays focused on the compaction cycle and schema
 * evolution lives in one place.
 */
final class CompactionFinishedPayloads {

    static final String OUTCOME_ATTEMPTED_NO_CHANGE = "attempted_no_change";
    static final String OUTCOME_COMPACTED = "compacted";
    static final String OUTCOME_ERROR = "error";

    private CompactionFinishedPayloads() {
    }

    static Map<String, Object> success(CompactionResult result, int keepLast, CompactionReason reason) {
        Map<String, Object> payload = base(keepLast, reason, OUTCOME_COMPACTED);
        payload.put("removed", result.removed());
        payload.put("usedSummary", result.usedSummary());
        if (result.details() != null) {
            payload.put("summaryLength", result.details().summaryLength());
            payload.put("splitTurnDetected", result.details().splitTurnDetected());
            payload.put("toolCount", result.details().toolCount());
            payload.put("readFilesCount", result.details().readFilesCount());
            payload.put("modifiedFilesCount", result.details().modifiedFilesCount());
            payload.put("durationMs", result.details().durationMs());
        }
        return payload;
    }

    static Map<String, Object> noChange(int keepLast, CompactionReason reason) {
        return base(keepLast, reason, OUTCOME_ATTEMPTED_NO_CHANGE);
    }

    static Map<String, Object> error(int observedSessionSize, CompactionReason reason, Throwable error) {
        // The error path keeps kept=0 to match the removed=0/kept=0 convention
        // shared with the success no-op path, and surfaces the observed session
        // size at time of catch via a dedicated sessionSize field. Overloading
        // "kept" with session size would make dashboards unable to distinguish
        // "nothing happened" from "everything was retained".
        Map<String, Object> payload = base(0, reason, OUTCOME_ERROR);
        payload.put("sessionSize", observedSessionSize);
        payload.put("errorType", error.getClass().getName());
        payload.put("errorMessage", error.getMessage() != null ? error.getMessage() : "");
        return payload;
    }

    /**
     * Builds the shared base schema for every COMPACTION_FINISHED payload. Each
     * outcome path (success, no-change, error) may add path-specific fields on top
     * of the base (e.g. errorType/errorMessage on error, durationMs on success),
     * but the keys defined here are guaranteed to be present in every variant so
     * dashboards never see an asymmetric base schema.
     */
    private static Map<String, Object> base(int kept, CompactionReason reason, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summaryLength", 0);
        payload.put("removed", 0);
        payload.put("kept", kept);
        payload.put("splitTurnDetected", false);
        payload.put("usedSummary", false);
        payload.put("reason", reason.name());
        payload.put("toolCount", 0);
        payload.put("readFilesCount", 0);
        payload.put("modifiedFilesCount", 0);
        payload.put("durationMs", 0);
        payload.put("outcome", outcome);
        return payload;
    }
}
