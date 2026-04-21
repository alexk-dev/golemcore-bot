package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

/**
 * Verdict emitted by the follow-through classifier.
 *
 * <p>
 * {@code hasUnfulfilledCommitment} is true only when {@code intentType ==
 * COMMITMENT} and the classifier concluded the commitment was not carried out
 * in the current turn. The classifier may still emit a legacy
 * {@code continuationPrompt}, but server-side code must ignore it and build the
 * internal nudge from a fixed safe template.
 */
public record ClassifierVerdict(IntentType intentType,boolean hasUnfulfilledCommitment,CommitmentCategory commitmentCategory,RiskLevel riskLevel,String commitmentText,String continuationPrompt,String reason){

public static ClassifierVerdict nonCommitment(IntentType intentType,String reason){return new ClassifierVerdict(intentType,false,CommitmentCategory.UNKNOWN,RiskLevel.HIGH,null,null,reason);}

public static ClassifierVerdict nonCommitment(IntentType intentType,CommitmentCategory commitmentCategory,RiskLevel riskLevel,String commitmentText,String reason){return new ClassifierVerdict(intentType,false,normalizeCategory(commitmentCategory),normalizeRisk(riskLevel),commitmentText,null,reason);}

public static ClassifierVerdict unfulfilledCommitment(CommitmentCategory commitmentCategory,RiskLevel riskLevel,String commitmentText,String continuationPrompt,String reason){return new ClassifierVerdict(IntentType.COMMITMENT,true,normalizeCategory(commitmentCategory),normalizeRisk(riskLevel),commitmentText,continuationPrompt,reason);}

public static ClassifierVerdict fulfilledCommitment(String commitmentText,String reason){return fulfilledCommitment(CommitmentCategory.UNKNOWN,RiskLevel.HIGH,commitmentText,reason);}

public static ClassifierVerdict fulfilledCommitment(CommitmentCategory commitmentCategory,RiskLevel riskLevel,String commitmentText,String reason){return new ClassifierVerdict(IntentType.COMMITMENT,false,normalizeCategory(commitmentCategory),normalizeRisk(riskLevel),commitmentText,null,reason);}

private static CommitmentCategory normalizeCategory(CommitmentCategory commitmentCategory){return commitmentCategory!=null?commitmentCategory:CommitmentCategory.UNKNOWN;}

private static RiskLevel normalizeRisk(RiskLevel riskLevel){return riskLevel!=null?riskLevel:RiskLevel.HIGH;}}
