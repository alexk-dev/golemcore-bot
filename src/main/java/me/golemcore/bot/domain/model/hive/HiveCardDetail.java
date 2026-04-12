/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.domain.model.hive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown=true)public record HiveCardDetail(String id,String serviceId,String boardId,String kind,String parentCardId,String epicCardId,List<String>dependsOnCardIds,String reviewOfCardId,List<String>reviewerGolemIds,String reviewerTeamId,Integer requiredReviewCount,String reviewStatus,String teamId,String objectiveId,String threadId,String title,String description,String prompt,String columnId,String assigneeGolemId,String assignmentPolicy,Integer position,boolean archived,Instant archivedAt,Instant createdAt,Instant updatedAt,Instant lastTransitionAt){

public HiveCardDetail{dependsOnCardIds=dependsOnCardIds!=null?List.copyOf(dependsOnCardIds):List.of();reviewerGolemIds=reviewerGolemIds!=null?List.copyOf(reviewerGolemIds):List.of();}}
