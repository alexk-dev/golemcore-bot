package me.golemcore.bot.adapter.inbound.web.controller;

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

import me.golemcore.bot.application.selfevolving.tactic.TacticEmbeddingProbeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Dashboard endpoint that probes an embedding provider using the current form
 * values plus the stored API key fallback.
 */
@RestController
@RequestMapping("/api/self-evolving/tactics/embeddings")
public class TacticEmbeddingProbeController {

    private final TacticEmbeddingProbeService tacticEmbeddingProbeService;

    public TacticEmbeddingProbeController(TacticEmbeddingProbeService tacticEmbeddingProbeService) {
        this.tacticEmbeddingProbeService = tacticEmbeddingProbeService;
    }

    public record ProbeRequest(
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions,
            Integer timeoutMs) {
    }

    public record ProbeResponse(
            boolean ok,
            String model,
            Integer dimensions,
            Integer vectorLength,
            String baseUrl,
            String error) {
    }

    @PostMapping("/probe")
    public Mono<ResponseEntity<ProbeResponse>> probeRemoteEmbedding(
            @RequestBody(required = false) ProbeRequest request) {
        return Mono.fromCallable(() -> tacticEmbeddingProbeService.probe(
                request != null
                        ? new TacticEmbeddingProbeService.ProbeRequest(
                                request.baseUrl(),
                                request.apiKey(),
                                request.model(),
                                request.dimensions(),
                                request.timeoutMs())
                        : null))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponseEntity);
    }

    private ResponseEntity<ProbeResponse> toResponseEntity(
            TacticEmbeddingProbeService.EmbeddingProbeResult result) {
        return ResponseEntity.ok(new ProbeResponse(
                result.ok(),
                result.model(),
                result.dimensions(),
                result.vectorLength(),
                result.baseUrl(),
                result.error()));
    }
}
