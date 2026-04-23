package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.resilience.AbstractClassifierVerdictParser;
import me.golemcore.bot.domain.resilience.RiskLevel;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.springframework.stereotype.Component;

/**
 * Parses the strict-JSON verdict returned by the follow-through classifier LLM
 * call into a {@link ClassifierVerdict}.
 */
@Component
public class FollowThroughVerdictParser extends AbstractClassifierVerdictParser {

    public FollowThroughVerdictParser(TraceSnapshotCodecPort codec) {
        super(codec);
    }

    public ClassifierVerdict parse(String rawResponse) {
        return parseResponse(rawResponse, VerdictDto.class, this::buildVerdictFromDto,
                reason -> ClassifierVerdict.nonCommitment(IntentType.UNKNOWN, reason));
    }

    private ClassifierVerdict buildVerdictFromDto(VerdictDto dto) {
        IntentType intent = resolveEnum(dto.intentType(), IntentType.class, IntentType.UNKNOWN);
        CommitmentCategory commitmentCategory = resolveEnum(dto.commitmentCategory(), CommitmentCategory.class,
                CommitmentCategory.UNKNOWN);
        RiskLevel riskLevel = resolveEnum(dto.riskLevel(), RiskLevel.class, RiskLevel.HIGH);
        String reason = trimToNull(dto.reason());
        String commitmentText = trimToNull(dto.commitmentText());
        String continuationPrompt = trimToNull(dto.continuationPrompt());
        boolean rawFlag = Boolean.TRUE.equals(dto.hasUnfulfilledCommitment());

        if (intent != IntentType.COMMITMENT) {
            return ClassifierVerdict.nonCommitment(intent, commitmentCategory, riskLevel, commitmentText, reason);
        }
        if (!rawFlag) {
            return ClassifierVerdict.fulfilledCommitment(commitmentCategory, riskLevel, commitmentText, reason);
        }
        return ClassifierVerdict.unfulfilledCommitment(commitmentCategory, riskLevel, commitmentText,
                continuationPrompt, reason);
    }
}
