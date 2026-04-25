package me.golemcore.bot.adapter.inbound.web.controller;

import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Shared helpers for the self-evolving REST controllers: boundedElastic
 * wrapping for blocking calls and required-query-param validation.
 */
final class SelfEvolvingControllerSupport {

    private SelfEvolvingControllerSupport() {
    }

    static <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    static void requireQueryParam(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required query param: " + name);
        }
    }
}
