package me.golemcore.bot.adapter.outbound.hive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JdkHiveControlChannelClient implements HiveControlChannelClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();

    private HiveControlChannelStatus status = HiveControlChannelStatus.disconnected();
    private String connectedGolemId;
    private String connectedUrl;

    public JdkHiveControlChannelClient(BotProperties botProperties, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(botProperties.getHttp().getConnectTimeout()))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public void connect(HiveSessionState sessionState, Consumer<HiveControlCommandEnvelope> commandConsumer) {
        if (sessionState == null || sessionState.getGolemId() == null || sessionState.getControlChannelUrl() == null) {
            throw new IllegalArgumentException("Hive session state must include golemId and controlChannelUrl");
        }
        synchronized (lock) {
            if (isAlreadyConnected(sessionState)) {
                return;
            }
        }
        disconnect("reconnect");
        synchronized (lock) {
            status = new HiveControlChannelStatus(
                    "CONNECTING",
                    null,
                    status.lastMessageAt(),
                    null,
                    status.lastReceivedCommandId(),
                    status.receivedCommandCount());
            connectedGolemId = sessionState.getGolemId();
            connectedUrl = sessionState.getControlChannelUrl();
        }
        WebSocket webSocket;
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(
                            HiveControlChannelUrlResolver.resolve(
                                    sessionState.getServerUrl(),
                                    sessionState.getControlChannelUrl(),
                                    sessionState.getAccessToken()),
                            new Listener(commandConsumer))
                    .join();
        } catch (RuntimeException exception) {
            synchronized (lock) {
                connectedGolemId = null;
                connectedUrl = null;
                webSocketRef.set(null);
                status = new HiveControlChannelStatus(
                        "ERROR",
                        null,
                        status.lastMessageAt(),
                        exception.getMessage(),
                        status.lastReceivedCommandId(),
                        status.receivedCommandCount());
            }
            throw new IllegalStateException("Failed to connect Hive control channel", exception);
        }
        boolean staleConnection = false;
        synchronized (lock) {
            if (Objects.equals(connectedGolemId, sessionState.getGolemId())
                    && Objects.equals(connectedUrl, sessionState.getControlChannelUrl())) {
                webSocketRef.set(webSocket);
            } else {
                staleConnection = true;
            }
        }
        if (staleConnection) {
            closeQuietly(webSocket, "stale-connect");
        }
    }

    @Override
    public void disconnect(String reason) {
        WebSocket webSocket;
        synchronized (lock) {
            webSocket = webSocketRef.getAndSet(null);
            connectedGolemId = null;
            connectedUrl = null;
            status = new HiveControlChannelStatus(
                    "DISCONNECTED",
                    null,
                    status.lastMessageAt(),
                    null,
                    status.lastReceivedCommandId(),
                    status.receivedCommandCount());
        }
        closeQuietly(webSocket, reason);
    }

    @Override
    public HiveControlChannelStatus getStatus() {
        synchronized (lock) {
            return status;
        }
    }

    private boolean isAlreadyConnected(HiveSessionState sessionState) {
        return "CONNECTED".equals(status.state())
                && Objects.equals(connectedGolemId, sessionState.getGolemId())
                && Objects.equals(connectedUrl, sessionState.getControlChannelUrl())
                && webSocketRef.get() != null;
    }

    private void closeQuietly(WebSocket webSocket, String reason) {
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason != null ? reason : "disconnect").join();
        } catch (RuntimeException exception) {
            log.debug("[Hive] Control channel close failed: {}", exception.getMessage());
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final Consumer<HiveControlCommandEnvelope> commandConsumer;

        private Listener(Consumer<HiveControlCommandEnvelope> commandConsumer) {
            this.commandConsumer = commandConsumer;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (lock) {
                status = new HiveControlChannelStatus(
                        "CONNECTED",
                        Instant.now(),
                        status.lastMessageAt(),
                        null,
                        status.lastReceivedCommandId(),
                        status.receivedCommandCount());
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                HiveControlCommandEnvelope envelope = objectMapper.readValue(data.toString(),
                        HiveControlCommandEnvelope.class);
                synchronized (lock) {
                    status = new HiveControlChannelStatus(
                            "CONNECTED",
                            status.connectedAt(),
                            Instant.now(),
                            null,
                            envelope.getCommandId(),
                            status.receivedCommandCount() + 1);
                }
                commandConsumer.accept(envelope);
            } catch (Exception exception) { // NOSONAR - must keep socket alive on malformed payload
                synchronized (lock) {
                    status = new HiveControlChannelStatus(
                            "ERROR",
                            status.connectedAt(),
                            status.lastMessageAt(),
                            exception.getMessage(),
                            status.lastReceivedCommandId(),
                            status.receivedCommandCount());
                }
                log.warn("[Hive] Failed to parse control command: {}", exception.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (lock) {
                webSocketRef.compareAndSet(webSocket, null);
                JdkHiveControlChannelClient.this.status = new HiveControlChannelStatus(
                        "DISCONNECTED",
                        null,
                        JdkHiveControlChannelClient.this.status.lastMessageAt(),
                        statusCode == WebSocket.NORMAL_CLOSURE ? null : reason,
                        JdkHiveControlChannelClient.this.status.lastReceivedCommandId(),
                        JdkHiveControlChannelClient.this.status.receivedCommandCount());
                connectedGolemId = null;
                connectedUrl = null;
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            synchronized (lock) {
                webSocketRef.compareAndSet(webSocket, null);
                status = new HiveControlChannelStatus(
                        "ERROR",
                        status.connectedAt(),
                        status.lastMessageAt(),
                        error != null ? error.getMessage() : "Unknown control channel error",
                        status.lastReceivedCommandId(),
                        status.receivedCommandCount());
                connectedGolemId = null;
                connectedUrl = null;
            }
        }
    }
}
