package me.golemcore.bot.adapter.inbound.web;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Centralized exception handler for dashboard controllers. Scoped to web
 * controllers only — excludes WebhookController (in adapter.inbound.webhook).
 */
@ControllerAdvice(basePackages = "me.golemcore.bot.adapter.inbound.web.controller")
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        log.warn("[API] {}: {}", status, ex.getReason());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(status.value())
                .message(ex.getReason())
                .build();
        return Mono.just(ResponseEntity.status(status).body(body));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[API] Bad request: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message(ex.getMessage())
                .build();
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleIllegalState(IllegalStateException ex) {
        log.warn("[API] Conflict: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .message(ex.getMessage())
                .build();
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(body));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleGeneric(Exception ex) {
        log.error("[API] Internal server error", ex);
        ApiErrorResponse body = ApiErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .message("Internal server error")
                .build();
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body));
    }
}
