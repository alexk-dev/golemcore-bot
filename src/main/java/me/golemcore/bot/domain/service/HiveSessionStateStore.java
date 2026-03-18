package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HiveSessionStateStore {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String SESSION_STATE_FILE = "hive-session.json";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private HiveSessionState cachedState;
    private boolean loaded;

    public Optional<HiveSessionState> load() {
        ensureLoaded();
        synchronized (lock) {
            return Optional.ofNullable(copy(cachedState));
        }
    }

    public void save(HiveSessionState sessionState) {
        if (sessionState == null) {
            throw new IllegalArgumentException("Hive session state is required");
        }
        HiveSessionState snapshot = copy(sessionState);
        synchronized (lock) {
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
                storagePort.putTextAtomic(PREFERENCES_DIR, SESSION_STATE_FILE, json, true).join();
                cachedState = snapshot;
                loaded = true;
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to persist Hive session state", exception);
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            try {
                storagePort.deleteObject(PREFERENCES_DIR, SESSION_STATE_FILE).join();
            } catch (RuntimeException exception) {
                log.warn("[Hive] Failed to delete persisted session state: {}", exception.getMessage());
            }
            cachedState = null;
            loaded = true;
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            cachedState = loadState();
            loaded = true;
        }
    }

    private HiveSessionState loadState() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, SESSION_STATE_FILE).join();
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, HiveSessionState.class);
        } catch (IOException | RuntimeException exception) { // NOSONAR - startup should degrade gracefully
            log.warn("[Hive] Failed to load persisted session state: {}", exception.getMessage());
            return null;
        }
    }

    private HiveSessionState copy(HiveSessionState source) {
        if (source == null) {
            return null;
        }
        return HiveSessionState.builder()
                .schemaVersion(source.getSchemaVersion())
                .golemId(source.getGolemId())
                .serverUrl(source.getServerUrl())
                .controlChannelUrl(source.getControlChannelUrl())
                .issuer(source.getIssuer())
                .audience(source.getAudience())
                .accessToken(source.getAccessToken())
                .refreshToken(source.getRefreshToken())
                .accessTokenExpiresAt(source.getAccessTokenExpiresAt())
                .refreshTokenExpiresAt(source.getRefreshTokenExpiresAt())
                .heartbeatIntervalSeconds(source.getHeartbeatIntervalSeconds())
                .scopes(source.getScopes() != null ? new ArrayList<>(source.getScopes()) : new ArrayList<>())
                .registeredAt(source.getRegisteredAt())
                .lastConnectedAt(source.getLastConnectedAt())
                .lastHeartbeatAt(source.getLastHeartbeatAt())
                .lastTokenRotatedAt(source.getLastTokenRotatedAt())
                .lastError(source.getLastError())
                .build();
    }
}
