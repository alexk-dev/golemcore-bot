package me.golemcore.bot.port.outbound.selfevolving;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbound port for a persistent tactic embedding index keyed by (provider,
 * model, tacticId). Implementations own the storage backend (SQLite, in-memory,
 * etc.) and schema.
 */
public interface TacticEmbeddingIndexPort {

    void replaceAll(String provider, String model, Integer dimensions, List<Entry> entries);

    Map<String, Entry> loadEntries(String provider, String model);

    boolean hasEntry(String tacticId, String provider, String model);

    record Entry(
            String tacticId,
            String contentRevisionId,
            Integer dimensions,
            List<Double> vector,
            Instant updatedAt) {

        public Entry(String tacticId, String contentRevisionId, List<Double> vector, Instant updatedAt) {
            this(tacticId, contentRevisionId, null, vector, updatedAt);
        }
    }
}
