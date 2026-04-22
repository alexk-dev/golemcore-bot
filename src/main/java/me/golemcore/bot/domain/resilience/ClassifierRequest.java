package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

import java.util.List;

/**
 * Shared input passed to resilience classifiers.
 *
 * <p>
 * Carries the visible user request, the assistant reply being evaluated, and
 * the tool names already executed in the current turn so classifiers can decide
 * whether the assistant still owes a follow-up action.
 */
public record ClassifierRequest(String userMessage,String assistantReply,List<String>executedToolsInTurn){}
