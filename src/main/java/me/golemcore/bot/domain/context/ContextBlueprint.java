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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A declarative description of all context layers that were assembled for a
 * single agent turn.
 *
 * <p>
 * The blueprint is built incrementally by {@link ContextAssembler} as each
 * {@link ContextLayer} produces its {@link ContextLayerResult}. It serves two
 * purposes:
 * <ol>
 * <li>Input to {@link PromptComposer} for composing the final system prompt
 * string from ordered layer results.</li>
 * <li>Diagnostic artifact — downstream systems can inspect which layers
 * contributed, their token estimates, and attached metadata.</li>
 * </ol>
 *
 * <p>
 * Results are stored in insertion order, which must match the layer ordering
 * enforced by the assembler. Use {@link #getResults()} for the ordered list and
 * {@link #getResult(String)} to look up by layer name.
 */
public class ContextBlueprint {

    private final List<ContextLayerResult> results = new ArrayList<>();
    private final Map<String, ContextLayerResult> resultsByName = new LinkedHashMap<>();

    /**
     * Creates a new empty blueprint.
     *
     * @return a fresh blueprint ready to accept layer results
     */
    public static ContextBlueprint create() {
        return new ContextBlueprint();
    }

    /**
     * Adds a layer result to this blueprint. Results are stored in insertion order.
     *
     * @param result
     *            the layer result to add
     */
    public void add(ContextLayerResult result) {
        if (result == null) {
            return;
        }
        results.add(result);
        if (result.getLayerName() != null) {
            resultsByName.put(result.getLayerName(), result);
        }
    }

    /**
     * Returns all layer results in assembly order.
     *
     * @return unmodifiable ordered list of results
     */
    public List<ContextLayerResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * Looks up a specific layer result by name.
     *
     * @param layerName
     *            the layer name
     * @return the result if present
     */
    public Optional<ContextLayerResult> getResult(String layerName) {
        return Optional.ofNullable(resultsByName.get(layerName));
    }

    /**
     * Returns the total estimated token count across all layers.
     *
     * @return sum of all layer token estimates
     */
    public int getTotalEstimatedTokens() {
        return results.stream()
                .mapToInt(ContextLayerResult::getEstimatedTokens)
                .sum();
    }

    /**
     * Returns only those results that carry non-blank content.
     *
     * @return unmodifiable list of results with content
     */
    public List<ContextLayerResult> getContentResults() {
        return results.stream()
                .filter(ContextLayerResult::hasContent)
                .toList();
    }
}
