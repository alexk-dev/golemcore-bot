package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the strict-JSON verdict returned by the follow-through classifier LLM
 * call into a {@link ClassifierVerdict}.
 *
 * <p>
 * Decoding goes through {@link TraceSnapshotCodecPort} so that Jackson stays on
 * the adapter side of the hexagonal boundary. Fenced or trailing-prose LLM
 * output is extracted with a plain regex before decoding.
 *
 * <p>
 * Parsing is resilient: malformed JSON, unexpected fields, or unknown
 * intent_type values collapse to a non-commitment {@link IntentType#UNKNOWN}
 * verdict so that a classifier hiccup cannot cause a false-positive nudge.
 *
 * <p>
 * Semantic guards: only {@link IntentType#COMMITMENT} with a non-blank
 * {@code continuation_prompt} is allowed to flip {@code
 * hasUnfulfilledCommitment} to {@code true}. Everything else is forced to
 * {@code false}.
 */
@Component
public class FollowThroughVerdictParser {

    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private final TraceSnapshotCodecPort codec;

    public FollowThroughVerdictParser(TraceSnapshotCodecPort codec) {
        this.codec = codec;
    }

    public ClassifierVerdict parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return ClassifierVerdict.nonCommitment(IntentType.UNKNOWN, "classifier returned empty response");
        }
        String trimmed = rawResponse.trim();

        Matcher fenced = FENCED_JSON.matcher(trimmed);
        if (fenced.find()) {
            return decodeAndBuild(fenced.group(1));
        }

        String lastError = null;
        int cursor = 0;
        while (cursor < trimmed.length()) {
            int start = trimmed.indexOf('{', cursor);
            if (start < 0) {
                break;
            }
            int end = findMatchingBrace(trimmed, start);
            if (end < 0) {
                break;
            }
            String candidate = trimmed.substring(start, end + 1);
            try {
                VerdictDto dto = codec.decodeJson(candidate, VerdictDto.class);
                if (dto != null) {
                    return buildVerdictFromDto(dto);
                }
                lastError = "decoded to null";
            } catch (IllegalArgumentException exception) {
                lastError = exception.getMessage();
            }
            cursor = end + 1;
        }

        if (lastError != null) {
            return ClassifierVerdict.nonCommitment(IntentType.UNKNOWN,
                    "classifier response not parseable as JSON: " + lastError);
        }
        return ClassifierVerdict.nonCommitment(IntentType.UNKNOWN, "classifier response contained no JSON object");
    }

    private ClassifierVerdict decodeAndBuild(String json) {
        VerdictDto dto;
        try {
            dto = codec.decodeJson(json, VerdictDto.class);
        } catch (IllegalArgumentException exception) {
            return ClassifierVerdict.nonCommitment(IntentType.UNKNOWN,
                    "classifier response not parseable as JSON: " + exception.getMessage());
        }
        if (dto == null) {
            return ClassifierVerdict.nonCommitment(IntentType.UNKNOWN, "classifier response decoded to null");
        }
        return buildVerdictFromDto(dto);
    }

    private ClassifierVerdict buildVerdictFromDto(VerdictDto dto) {
        IntentType intent = resolveIntent(dto.intentType());
        String reason = trim(dto.reason());
        String commitmentText = trim(dto.commitmentText());
        String continuationPrompt = trim(dto.continuationPrompt());
        boolean rawFlag = Boolean.TRUE.equals(dto.hasUnfulfilledCommitment());

        if (intent != IntentType.COMMITMENT) {
            return new ClassifierVerdict(intent, false, commitmentText, null, reason);
        }
        if (!rawFlag) {
            return ClassifierVerdict.fulfilledCommitment(commitmentText, reason);
        }
        if (continuationPrompt == null) {
            return ClassifierVerdict.fulfilledCommitment(commitmentText,
                    reason != null ? reason : "commitment detected but continuation_prompt missing");
        }
        return ClassifierVerdict.unfulfilledCommitment(commitmentText, continuationPrompt, reason);
    }

    private int findMatchingBrace(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private IntentType resolveIntent(String raw) {
        if (raw == null) {
            return IntentType.UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return IntentType.UNKNOWN;
        }
        try {
            return IntentType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return IntentType.UNKNOWN;
        }
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
