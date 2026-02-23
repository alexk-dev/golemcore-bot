package me.golemcore.bot.adapter.inbound.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.LogEntryDto;
import me.golemcore.bot.adapter.inbound.web.logstream.DashboardLogService;
import me.golemcore.bot.adapter.inbound.web.security.JwtTokenProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketLogsHandler implements WebSocketHandler {

    private static final int MAX_EVENTS_PER_BATCH = 100;
    private static final Duration BATCH_TIMEOUT = Duration.ofMillis(200);

    private final JwtTokenProvider jwtTokenProvider;
    private final DashboardLogService dashboardLogService;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String token = extractQueryParam(session, "token");
        if (token == null || !jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            log.warn("[LogsWS] Connection rejected: invalid or missing JWT");
            return session.close();
        }

        long afterSeq = parseAfterSeq(extractQueryParam(session, "afterSeq"));
        String username = jwtTokenProvider.getUsernameFromToken(token);
        log.info("[LogsWS] Connection established: user={}, afterSeq={}", username, afterSeq);

        Flux<WebSocketMessage> outbound = dashboardLogService.streamAfter(afterSeq)
                .bufferTimeout(MAX_EVENTS_PER_BATCH, BATCH_TIMEOUT)
                .filter(batch -> !batch.isEmpty())
                .map(this::toPayloadJson)
                .map(session::textMessage);

        return session.send(outbound)
                .and(session.receive().then())
                .doFinally(signal -> log.info("[LogsWS] Connection closed: user={}, signal={}", username, signal));
    }

    private String toPayloadJson(List<LogEntryDto> batch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "log_batch");
        payload.put("items", batch);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long parseAfterSeq(String afterSeqRaw) {
        if (afterSeqRaw == null || afterSeqRaw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(afterSeqRaw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String extractQueryParam(WebSocketSession session, String key) {
        URI uri = session.getHandshakeInfo().getUri();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        return UriComponentsBuilder.newInstance()
                .query(query)
                .build()
                .getQueryParams()
                .getFirst(key);
    }
}
