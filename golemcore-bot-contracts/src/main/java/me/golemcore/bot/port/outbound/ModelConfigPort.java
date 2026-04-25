package me.golemcore.bot.port.outbound;

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

import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;

import java.util.List;
import java.util.Map;

/**
 * Port for querying the model catalog. Domain services depend on this interface
 * instead of the infrastructure ModelConfigService directly.
 */
public interface ModelConfigPort {

    /**
     * Look up settings for a model by name. Throws if not found in the catalog.
     */
    ModelCatalogEntry getModelSettings(String modelName);

    /** All registered models. */
    Map<String, ModelCatalogEntry> getAllModels();

    /** Models filtered by provider names. */
    Map<String, ModelCatalogEntry> getModelsForProviders(List<String> providers);

    /** Provider id for a model. */
    String getProvider(String modelName);

    /** Whether the model requires a reasoning parameter. */
    boolean isReasoningRequired(String modelName);

    /** Whether the model accepts a temperature parameter. */
    boolean supportsTemperature(String modelName);

    /** Whether the model accepts image/vision inputs. */
    boolean supportsVision(String modelName);

    /** Maximum input tokens for a model (highest across reasoning levels). */
    int getMaxInputTokens(String modelName);

    /** Maximum input tokens at a specific reasoning level. */
    int getMaxInputTokens(String modelName, String reasoningLevel);

    /** Available reasoning levels for a model. */
    List<String> getAvailableReasoningLevels(String modelName);

    /** Lowest reasoning level for a model (by preference order). */
    String getLowestReasoningLevel(String modelName);
}
