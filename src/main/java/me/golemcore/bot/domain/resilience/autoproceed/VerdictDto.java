package me.golemcore.bot.domain.resilience.autoproceed;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTO for the Auto-Proceed classifier LLM JSON response. Kept
 * package-private and only touched by {@link AutoProceedVerdictParser}.
 */
@JsonIgnoreProperties(ignoreUnknown=true)record VerdictDto(@JsonProperty("intent_type")String intentType,@JsonProperty("should_auto_affirm")Boolean shouldAutoAffirm,@JsonProperty("question_text")String questionText,@JsonProperty("affirmation_prompt")String affirmationPrompt,@JsonProperty("reason")String reason){}
