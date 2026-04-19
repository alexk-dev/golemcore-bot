package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

import java.util.List;

/**
 * Input to the follow-through classifier.
 *
 * <p>
 * Holds only the data required to judge whether the assistant's final reply
 * contains an unfulfilled commitment.
 */
public record ClassifierRequest(String userMessage,String assistantReply,List<String>executedToolsInTurn){

public ClassifierRequest{executedToolsInTurn=executedToolsInTurn==null?List.of():List.copyOf(executedToolsInTurn);}}
