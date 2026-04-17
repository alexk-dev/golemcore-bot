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

/**
 * Runtime metadata for a single auto execution tick.
 *
 * @param sessionIdentity
 *            logical session identity for the tick
 * @param runKind
 *            kind of auto run
 * @param runId
 *            auto run identifier
 * @param goalId
 *            optional goal identifier
 * @param taskId
 *            optional task identifier
 */
public record AutoExecutionContext(SessionIdentity sessionIdentity,AutoRunKind runKind,String runId,String goalId,String taskId){}
