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
 * in the current turn. For every other intent type the flag is forced false.
 */
public record ClassifierVerdict(IntentType intentType,boolean hasUnfulfilledCommitment,String commitmentText,String continuationPrompt,String reason){

public static ClassifierVerdict nonCommitment(IntentType intentType,String reason){return new ClassifierVerdict(intentType,false,null,null,reason);}

public static ClassifierVerdict unfulfilledCommitment(String commitmentText,String continuationPrompt,String reason){return new ClassifierVerdict(IntentType.COMMITMENT,true,commitmentText,continuationPrompt,reason);}

public static ClassifierVerdict fulfilledCommitment(String commitmentText,String reason){return new ClassifierVerdict(IntentType.COMMITMENT,false,commitmentText,null,reason);}}
