package me.golemcore.bot.domain.model.selfevolving;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of runtime artifacts used by a run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactBundleRecord {

    private String id;
    private String golemId;
    private String sourceRunId;
    private String sourceCandidateId;
    private String status;
    private Instant createdAt;
    private Instant activatedAt;

    @Builder.Default
    private List<String> skillVersions = new ArrayList<>();

    @Builder.Default
    private List<String> promptVersions = new ArrayList<>();

    @Builder.Default
    private List<String> policyVersions = new ArrayList<>();

    @Builder.Default
    private Map<String, String> artifactRevisionBindings = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, String> artifactKeyBindings = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, String> artifactTypeBindings = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, String> artifactSubtypeBindings = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, String> tierBindings = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> configSnapshot = new LinkedHashMap<>();
}
