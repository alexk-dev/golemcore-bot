package me.golemcore.bot.domain.resilience.followthrough;

/*
 * Copyright 2026 Aleksei Kuleshov SPDX-License-Identifier: Apache-2.0 Contact:
 * alex@kuleshov.tech
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTO for the classifier LLM JSON response. Kept package-private and only
 * touched by {@link FollowThroughVerdictParser}. Jackson annotations are fine
 * in domain code (only the ObjectMapper/JsonNode runtime types are forbidden by
 * the hexagonal architecture contract).
 */
@JsonIgnoreProperties(ignoreUnknown=true)record VerdictDto(@JsonProperty("intent_type")String intentType,@JsonProperty("has_unfulfilled_commitment")Boolean hasUnfulfilledCommitment,@JsonProperty("commitment_category")String commitmentCategory,@JsonProperty("risk_level")String riskLevel,@JsonProperty("commitment_text")String commitmentText,@JsonProperty("continuation_prompt")String continuationPrompt,@JsonProperty("reason")String reason){}
