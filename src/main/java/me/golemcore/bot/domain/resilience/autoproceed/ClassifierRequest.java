package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

import java.util.List;

/**
 * Input to the Auto-Proceed classifier.
 *
 * <p>
 * Holds the data required to decide whether the assistant's final reply ended
 * with a rhetorical confirmation question that has a single obvious forward
 * path (so an auto-affirmation is safe).
 */
public record ClassifierRequest(String userMessage,String assistantReply,List<String>executedToolsInTurn){

public ClassifierRequest{executedToolsInTurn=executedToolsInTurn==null?List.of():List.copyOf(executedToolsInTurn);}}
