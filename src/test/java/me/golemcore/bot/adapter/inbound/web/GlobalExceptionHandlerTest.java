package me.golemcore.bot.adapter.inbound.web;

import me.golemcore.bot.adapter.inbound.web.dto.ApiErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import reactor.test.StepVerifier;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void shouldHandleResponseStatusException() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 'x' not found");

        StepVerifier.create(handler.handleResponseStatus(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(404, body.getStatus());
                    assertEquals("Skill 'x' not found", body.getMessage());
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Provider name must match [a-z0-9][a-z0-9_-]*");

        StepVerifier.create(handler.handleIllegalArgument(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(400, body.getStatus());
                    assertEquals("Provider name must match [a-z0-9][a-z0-9_-]*", body.getMessage());
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleIllegalStateException() {
        IllegalStateException ex = new IllegalStateException("Resource conflict");

        StepVerifier.create(handler.handleIllegalState(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(409, body.getStatus());
                    assertEquals("Resource conflict", body.getMessage());
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleGenericException() {
        Exception ex = new RuntimeException("Unexpected failure");

        StepVerifier.create(handler.handleGeneric(ex))
                .assertNext(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                    ApiErrorResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(500, body.getStatus());
                    assertEquals("Internal server error", body.getMessage());
                })
                .verifyComplete();
    }
}
