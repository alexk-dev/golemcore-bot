package me.golemcore.bot.adapter.outbound.hive;

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

import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder public record HiveEventPayload(Integer schemaVersion,String eventType,String golemId,String runtimeEventType,String signalId,String cardId,String commandId,String requestId,String runId,String threadId,String signalType,String summary,String details,String blockerCode,List<HiveEvidenceRef>evidenceRefs,Long inputTokens,Long outputTokens,Long accumulatedCostMicros,String operation,Boolean success,String errorCode,String errorMessage,Object payload,Instant createdAt){}
