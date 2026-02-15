package me.golemcore.bot.domain.model;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * Contact: alex@kuleshov.tech
 */

import java.util.Objects;

/**
 * Machine-readable request to transition the active skill.
 *
 * <p>
 * This is an internal pipeline control signal (stored in {@link AgentContext}
 * attributes) that tells
 * {@link me.golemcore.bot.domain.system.ContextBuildingSystem} to load and
 * activate a different skill for the next iteration.
 */
public record SkillTransitionRequest(String targetSkill,SkillTransitionReason reason){

public SkillTransitionRequest{Objects.requireNonNull(targetSkill,"targetSkill");Objects.requireNonNull(reason,"reason");}

public static SkillTransitionRequest explicit(String targetSkill){return new SkillTransitionRequest(targetSkill,SkillTransitionReason.EXPLICIT_TOOL);}

public static SkillTransitionRequest pipeline(String targetSkill){return new SkillTransitionRequest(targetSkill,SkillTransitionReason.SKILL_PIPELINE);}}
