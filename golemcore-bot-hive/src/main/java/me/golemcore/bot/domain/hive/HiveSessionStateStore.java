package me.golemcore.bot.domain.hive;

import java.util.ArrayList;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.port.outbound.HiveSessionStatePort;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HiveSessionStateStore {

    private final HiveSessionStatePort hiveSessionStatePort;

    private final Object lock = new Object();
    private HiveSessionState cachedState;
    private volatile boolean loaded;

    public HiveSessionStateStore(HiveSessionStatePort hiveSessionStatePort) {
        this.hiveSessionStatePort = hiveSessionStatePort;
    }

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
            hiveSessionStatePort.save(snapshot);
            cachedState = snapshot;
            loaded = true;
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    public void clear() {
        synchronized (lock) {
            hiveSessionStatePort.clear();
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
        return hiveSessionStatePort.load().orElse(null);
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
