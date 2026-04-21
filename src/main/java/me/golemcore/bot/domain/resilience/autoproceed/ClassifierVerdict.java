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
 * {@code intentType == RHETORICAL_CONFIRM} and the classifier authored a
 * non-blank {@code affirmationPrompt}. For every other intent type the flag is
 * forced to false.
 */
public record ClassifierVerdict(IntentType intentType,boolean shouldAutoAffirm,String questionText,String affirmationPrompt,String reason){

public static ClassifierVerdict nonActionable(IntentType intentType,String reason){return new ClassifierVerdict(intentType,false,null,null,reason);}

public static ClassifierVerdict affirm(String questionText,String affirmationPrompt,String reason){return new ClassifierVerdict(IntentType.RHETORICAL_CONFIRM,true,questionText,affirmationPrompt,reason);}}
