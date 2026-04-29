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

package me.golemcore.bot.domain.hive;

import java.util.List;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.domain.model.hive.HiveCardSummary;
import me.golemcore.bot.domain.model.hive.HiveCreateCardRequest;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import me.golemcore.bot.domain.model.hive.HiveThreadMessage;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.springframework.stereotype.Service;

@Service
public class HiveSdlcService {

    private final RuntimeConfigQueryPort runtimeConfigQueryPort;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final HiveGatewayPort hiveGatewayPort;

    public HiveSdlcService(
            RuntimeConfigQueryPort runtimeConfigQueryPort,
            HiveSessionStateStore hiveSessionStateStore,
            HiveGatewayPort hiveGatewayPort) {
        this.runtimeConfigQueryPort = runtimeConfigQueryPort;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.hiveGatewayPort = hiveGatewayPort;
    }

    public HiveCardDetail getCard(String cardId) {
        HiveSessionState sessionState = resolveSessionState();
        return hiveGatewayPort.getCard(sessionState.getServerUrl(), sessionState.getGolemId(),
                sessionState.getAccessToken(), cardId);
    }

    public List<HiveCardSummary> searchCards(HiveCardSearchRequest request) {
        HiveSessionState sessionState = resolveSessionState();
        return hiveGatewayPort.searchCards(sessionState.getServerUrl(), sessionState.getGolemId(),
                sessionState.getAccessToken(), request);
    }

    public HiveCardDetail createCard(HiveCreateCardRequest request) {
        HiveSessionState sessionState = resolveSessionState();
        return hiveGatewayPort.createCard(sessionState.getServerUrl(), sessionState.getGolemId(),
                sessionState.getAccessToken(), request);
    }

    public HiveThreadMessage postThreadMessage(String threadId, String body) {
        HiveSessionState sessionState = resolveSessionState();
        return hiveGatewayPort.postThreadMessage(sessionState.getServerUrl(), sessionState.getGolemId(),
                sessionState.getAccessToken(), threadId, body);
    }

    public HiveCardDetail requestReview(String cardId, HiveRequestReviewRequest request) {
        HiveSessionState sessionState = resolveSessionState();
        return hiveGatewayPort.requestReview(sessionState.getServerUrl(), sessionState.getGolemId(),
                sessionState.getAccessToken(), cardId, request);
    }

    private HiveSessionState resolveSessionState() {
        if (!HiveRuntimeConfigSupport.isHiveEnabled(runtimeConfigQueryPort.getRuntimeConfig())) {
            throw new IllegalStateException("Hive integration is disabled");
        }
        HiveSessionState sessionState = hiveSessionStateStore.load()
                .orElseThrow(() -> new IllegalStateException("Hive session is not connected"));
        requireText(sessionState.getServerUrl(), "Hive server URL is missing");
        requireText(sessionState.getGolemId(), "Hive golem ID is missing");
        requireText(sessionState.getAccessToken(), "Hive access token is missing");
        return sessionState;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
