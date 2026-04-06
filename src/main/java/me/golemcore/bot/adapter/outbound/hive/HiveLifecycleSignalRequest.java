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

/**
 * Adapter compatibility wrapper for legacy Hive lifecycle request type.
 */
@Deprecated(forRemoval=false)public record HiveLifecycleSignalRequest(String signalType,String summary,String details,String blockerCode,List<HiveEvidenceRef>evidenceRefs,Instant createdAt){

public HiveLifecycleSignalRequest{evidenceRefs=evidenceRefs!=null?List.copyOf(evidenceRefs):List.of();}

public me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest toDomain(){List<me.golemcore.bot.domain.model.hive.HiveEvidenceRef>refs=evidenceRefs.stream().map(HiveEvidenceRef::toDomain).toList();return new me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest(signalType,summary,details,blockerCode,refs,createdAt);}

public static HiveLifecycleSignalRequest fromDomain(me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest request){if(request==null){return null;}List<HiveEvidenceRef>refs=request.evidenceRefs().stream().map(HiveEvidenceRef::fromDomain).toList();return new HiveLifecycleSignalRequest(request.signalType(),request.summary(),request.details(),request.blockerCode(),refs,request.createdAt());}}
