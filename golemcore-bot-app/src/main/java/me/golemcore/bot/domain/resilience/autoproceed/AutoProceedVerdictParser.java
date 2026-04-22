package me.golemcore.bot.domain.resilience.autoproceed;

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
 * Parses the strict-JSON verdict returned by the Auto-Proceed classifier LLM
 * call into a {@link ClassifierVerdict}.
 */
@Component
public class AutoProceedVerdictParser extends AbstractClassifierVerdictParser {

    public AutoProceedVerdictParser(TraceSnapshotCodecPort codec) {
        super(codec);
    }

    public ClassifierVerdict parse(String rawResponse) {
        return parseResponse(rawResponse, VerdictDto.class, this::buildVerdictFromDto,
                reason -> ClassifierVerdict.nonActionable(IntentType.UNKNOWN, reason));
    }

    private ClassifierVerdict buildVerdictFromDto(VerdictDto dto) {
        IntentType intent = resolveEnum(dto.intentType(), IntentType.class, IntentType.UNKNOWN);
        RiskLevel riskLevel = resolveEnum(dto.riskLevel(), RiskLevel.class, RiskLevel.HIGH);
        String reason = trimToNull(dto.reason());
        String questionText = trimToNull(dto.questionText());
        String affirmationPrompt = trimToNull(dto.affirmationPrompt());
        boolean rawFlag = Boolean.TRUE.equals(dto.shouldAutoAffirm());

        if (intent != IntentType.RHETORICAL_CONFIRM) {
            return ClassifierVerdict.nonActionable(intent, riskLevel, questionText, reason);
        }
        if (!rawFlag) {
            return ClassifierVerdict.nonActionable(IntentType.RHETORICAL_CONFIRM, riskLevel, questionText, reason);
        }
        return new ClassifierVerdict(IntentType.RHETORICAL_CONFIRM, true, riskLevel, questionText,
                affirmationPrompt, reason);
    }
}
