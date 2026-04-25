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

import java.util.Map;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionResponse;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.hive.HiveLifecycleSignalRequest;

/**
 * Outbound port for Hive-specific runtime event publishing operations.
 */
public interface HiveEventPublishPort extends RuntimeEventPublishPort {

    void publishCommandAcknowledged(HiveControlCommandEnvelope envelope);

    void publishInspectionResponse(HiveInspectionResponse response);

    void publishProgressUpdate(String threadId, ProgressUpdate update);

    void publishThreadMessage(String threadId, String content, Map<String, Object> metadata);

    void publishLifecycleSignal(HiveLifecycleSignalRequest request, Map<String, Object> metadata);
}
