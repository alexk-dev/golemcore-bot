package me.golemcore.bot.domain.service;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores active conversation pointers per channel/transport identity.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveSessionPointerService {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String POINTERS_FILE = "session-pointers.json";
    private static final String CHANNEL_WEB = "web";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String KEY_SEPARATOR = "|";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private final Map<String, String> pointers = new ConcurrentHashMap<>();

    private volatile boolean loaded = false;

    public String buildWebPointerKey(String username, String clientInstanceId) {
        return CHANNEL_WEB + KEY_SEPARATOR + normalizeSegment(username) + KEY_SEPARATOR + normalizeSegment(clientInstanceId);
    }

    public String buildTelegramPointerKey(String transportChatId) {
        return CHANNEL_TELEGRAM + KEY_SEPARATOR + normalizeSegment(transportChatId);
    }

    public Optional<String> getActiveConversationKey(String pointerKey) {
        ensureLoaded();
        return Optional.ofNullable(pointers.get(pointerKey));
    }

    public Map<String, String> getPointersSnapshot() {
        ensureLoaded();
        synchronized (lock) {
            return new LinkedHashMap<>(pointers);
        }
    }

    public void setActiveConversationKey(String pointerKey, String conversationKey) {
        ensureLoaded();
        if (pointerKey == null || pointerKey.isBlank()) {
            throw new IllegalArgumentException("pointerKey must not be blank");
        }
        if (conversationKey == null || conversationKey.isBlank()) {
            throw new IllegalArgumentException("conversationKey must not be blank");
        }

        synchronized (lock) {
            String previous = pointers.put(pointerKey, conversationKey);
            try {
                persistPointersLocked();
            } catch (RuntimeException e) {
                rollback(pointerKey, previous);
                throw e;
            }
        }
    }

    public void clearActiveConversationKey(String pointerKey) {
        ensureLoaded();
        if (pointerKey == null || pointerKey.isBlank()) {
            return;
        }

        synchronized (lock) {
            String previous = pointers.remove(pointerKey);
            if (previous == null) {
                return;
            }
            try {
                persistPointersLocked();
            } catch (RuntimeException e) {
                pointers.put(pointerKey, previous);
                throw e;
            }
        }
    }

    private void rollback(String pointerKey, String previous) {
        if (previous == null) {
            pointers.remove(pointerKey);
            return;
        }
        pointers.put(pointerKey, previous);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loadPointersLocked();
            loaded = true;
        }
    }

    private void loadPointersLocked() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, POINTERS_FILE).join();
            if (json == null || json.isBlank()) {
                return;
            }

            SessionPointerRegistry registry = objectMapper.readValue(json, SessionPointerRegistry.class);
            if (registry.getPointers() != null) {
                pointers.putAll(registry.getPointers());
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - fallback to empty registry
            log.warn("Failed to load active session pointers: {}", e.getMessage());
        }
    }

    private void persistPointersLocked() {
        try {
            SessionPointerRegistry registry = new SessionPointerRegistry();
            registry.setVersion(1);
            registry.setUpdatedAt(Instant.now().toString());
            registry.setPointers(new LinkedHashMap<>(pointers));
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry);
            storagePort.putTextAtomic(PREFERENCES_DIR, POINTERS_FILE, json, true).join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist active session pointers", e);
        }
    }

    private String normalizeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.trim();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SessionPointerRegistry {
        private int version = 1;
        private Map<String, String> pointers = new LinkedHashMap<>();
        private String updatedAt = Instant.now().toString();
    }
}
