package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class JdkHiveControlChannelClientTest {

    private JdkHiveControlChannelClient client;
    private DisposableServer server;

    @BeforeEach
    void setUp() {
        BotProperties botProperties = new BotProperties();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new JdkHiveControlChannelClient(botProperties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        client.disconnect("test-teardown");
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void shouldConnectReceiveControlCommandAndTrackStatus() throws Exception {
        CountDownLatch receivedLatch = new CountDownLatch(1);
        AtomicReference<String> requestUri = new AtomicReference<>();
        AtomicReference<HiveControlCommandEnvelope> receivedEnvelope = new AtomicReference<>();
        String controlPayload = """
                {"eventType":"command","commandId":"cmd-1","threadId":"thread-1","cardId":"card-1","runId":"run-1","golemId":"golem-1","body":"Inspect repository state","createdAt":"2026-03-18T00:00:01Z"}
                """;
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    requestUri.set(request.uri());
                    return response.sendWebsocket((inbound, outbound) -> outbound.sendString(Mono.just(controlPayload))
                            .then(Mono.never()));
                })
                .bindNow();

        client.connect(HiveSessionState.builder()
                .golemId("golem-1")
                .serverUrl("http://127.0.0.1:" + server.port())
                .controlChannelUrl("/ws/golems/control")
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .accessTokenExpiresAt(Instant.parse("2026-03-18T00:10:00Z"))
                .refreshTokenExpiresAt(Instant.parse("2026-03-19T00:10:00Z"))
                .heartbeatIntervalSeconds(30)
                .registeredAt(Instant.parse("2026-03-18T00:00:00Z"))
                .build(), envelope -> {
                    receivedEnvelope.set(envelope);
                    receivedLatch.countDown();
                });

        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS));
        assertEquals("/ws/golems/control?access_token=access-token", requestUri.get());

        HiveControlCommandEnvelope envelope = receivedEnvelope.get();
        assertNotNull(envelope);
        assertEquals("command", envelope.getEventType());
        assertEquals("cmd-1", envelope.getCommandId());
        assertEquals("thread-1", envelope.getThreadId());
        assertEquals("card-1", envelope.getCardId());
        assertEquals("run-1", envelope.getRunId());
        assertEquals("Inspect repository state", envelope.getBody());

        HiveControlChannelStatus status = client.getStatus();
        assertEquals("CONNECTED", status.state());
        assertNotNull(status.connectedAt());
        assertNotNull(status.lastMessageAt());
        assertEquals("cmd-1", status.lastReceivedCommandId());
        assertEquals(1, status.receivedCommandCount());
    }
}
