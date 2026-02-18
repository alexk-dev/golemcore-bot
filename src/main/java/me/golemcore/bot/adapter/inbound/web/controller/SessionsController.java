package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.SessionDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.SessionSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session browser and management endpoints.
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionsController {

    private final SessionPort sessionPort;

    @GetMapping
    public Mono<ResponseEntity<List<SessionSummaryDto>>> listSessions(
            @RequestParam(required = false) String channel) {
        List<AgentSession> sessions = sessionPort.listAll();
        List<SessionSummaryDto> dtos = sessions.stream()
                .filter(s -> channel == null || channel.equals(s.getChannelType()))
                .sorted(Comparator.comparing(
                        (AgentSession s) -> s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt())
                        .reversed())
                .map(this::toSummary)
                .toList();
        return Mono.just(ResponseEntity.ok(dtos));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<SessionDetailDto>> getSession(@PathVariable String id) {
        Optional<AgentSession> session = sessionPort.get(id);
        return session
                .map(s -> Mono.just(ResponseEntity.ok(toDetail(s))))
                .orElse(Mono.just(ResponseEntity.notFound().build()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String id) {
        sessionPort.delete(id);
        return Mono.just(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/compact")
    public Mono<ResponseEntity<Map<String, Object>>> compactSession(
            @PathVariable String id, @RequestParam(defaultValue = "20") int keepLast) {
        int removed = sessionPort.compactMessages(id, keepLast);
        return Mono.just(ResponseEntity.ok(Map.of("removed", removed)));
    }

    @PostMapping("/{id}/clear")
    public Mono<ResponseEntity<Void>> clearSession(@PathVariable String id) {
        sessionPort.clearMessages(id);
        return Mono.just(ResponseEntity.noContent().build());
    }

    private SessionSummaryDto toSummary(AgentSession session) {
        return SessionSummaryDto.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .messageCount(session.getMessages() != null ? session.getMessages().size() : 0)
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null)
                .build();
    }

    private SessionDetailDto toDetail(AgentSession session) {
        List<SessionDetailDto.MessageDto> messages = List.of();
        if (session.getMessages() != null) {
            messages = session.getMessages().stream()
                    .map(this::toMessageDto)
                    .toList();
        }
        return SessionDetailDto.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null)
                .messages(messages)
                .build();
    }

    private SessionDetailDto.MessageDto toMessageDto(Message msg) {
        String model = null;
        String modelTier = null;
        if (msg.getMetadata() != null) {
            Object modelValue = msg.getMetadata().get("model");
            if (modelValue instanceof String) {
                model = (String) modelValue;
            }
            Object tierValue = msg.getMetadata().get("modelTier");
            if (tierValue instanceof String) {
                modelTier = (String) tierValue;
            }
        }

        return SessionDetailDto.MessageDto.builder()
                .id(msg.getId())
                .role(msg.getRole())
                .content(msg.getContent())
                .timestamp(msg.getTimestamp() != null ? msg.getTimestamp().toString() : null)
                .hasToolCalls(msg.hasToolCalls())
                .hasVoice(msg.hasVoice())
                .model(model)
                .modelTier(modelTier)
                .build();
    }
}
