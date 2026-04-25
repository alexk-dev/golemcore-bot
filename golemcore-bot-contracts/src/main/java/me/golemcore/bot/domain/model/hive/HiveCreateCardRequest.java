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

import java.util.List;

public record HiveCreateCardRequest(String serviceId,String boardId,String title,String description,String prompt,String columnId,String kind,String parentCardId,String epicCardId,String reviewOfCardId,List<String>dependsOnCardIds,String teamId,String objectiveId,String assigneeGolemId,String assignmentPolicy,boolean autoAssign){

public HiveCreateCardRequest{dependsOnCardIds=dependsOnCardIds!=null?List.copyOf(dependsOnCardIds):List.of();}}
