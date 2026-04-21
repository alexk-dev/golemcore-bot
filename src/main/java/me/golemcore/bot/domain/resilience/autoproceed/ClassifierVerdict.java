package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

/**
 * Verdict emitted by the Auto-Proceed classifier.
 *
 * <p>
 * {@code shouldAutoAffirm} is true only when
 * {@code intentType == RHETORICAL_CONFIRM}. The classifier no longer authors an
 * affirmation prompt; the server builds a fixed safe internal affirmation. Any
 * legacy prompt field is kept only for compatibility/debugging and must be
 * ignored by callers.
 */
public record ClassifierVerdict(IntentType intentType,boolean shouldAutoAffirm,RiskLevel riskLevel,String questionText,String affirmationPrompt,String reason){

public static ClassifierVerdict nonActionable(IntentType intentType,String reason){return new ClassifierVerdict(intentType,false,RiskLevel.HIGH,null,null,reason);}

public static ClassifierVerdict nonActionable(IntentType intentType,RiskLevel riskLevel,String questionText,String reason){return new ClassifierVerdict(intentType,false,normalizeRisk(riskLevel),questionText,null,reason);}

public static ClassifierVerdict affirm(RiskLevel riskLevel,String questionText,String reason){return new ClassifierVerdict(IntentType.RHETORICAL_CONFIRM,true,normalizeRisk(riskLevel),questionText,null,reason);}

private static RiskLevel normalizeRisk(RiskLevel riskLevel){return riskLevel!=null?riskLevel:RiskLevel.HIGH;}}
