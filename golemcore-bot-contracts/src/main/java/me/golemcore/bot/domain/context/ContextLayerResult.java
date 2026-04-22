package me.golemcore.bot.domain.context;

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

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The output of a single {@link ContextLayer} after assembly.
 *
 * <p>
 * Each layer produces a result containing the rendered prompt content (a
 * markdown section to inject into the system prompt), the layer name for
 * identification, and an estimated token count for budget tracking.
 *
 * <p>
 * Layers may also attach arbitrary metadata via {@link #metadata}, which is
 * propagated alongside the assembled prompt for downstream consumers (e.g.,
 * diagnostics and tier resolution artifacts).
 *
 * <h2>Empty Results</h2> A result with {@code null} or blank {@link #content}
 * is treated as "nothing to contribute" and is excluded from the composed
 * prompt. Use {@link #empty(String)} for convenience.
 */
@Data
@Builder
public class ContextLayerResult {

    /** Layer name, matching {@link ContextLayer#getName()}. */
    private final String layerName;

    /**
     * Rendered prompt content (markdown). {@code null} or blank means "no
     * contribution".
     */
    private final String content;

    /** Estimated token count for this content (characters / 3.5, rounded up). */
    @Builder.Default
    private final int estimatedTokens = 0;

    /**
     * Arbitrary metadata produced by the layer (diagnostics, resolved values,
     * etc.).
     */
    @Builder.Default
    private final Map<String, Object> metadata = new LinkedHashMap<>();

    /**
     * Creates an empty result indicating the layer has nothing to contribute.
     *
     * @param layerName
     *            the name of the layer
     * @return an empty result with no content and zero tokens
     */
    public static ContextLayerResult empty(String layerName) {
        return ContextLayerResult.builder()
                .layerName(layerName)
                .content(null)
                .estimatedTokens(0)
                .build();
    }

    /**
     * Returns {@code true} if this result carries prompt content.
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
