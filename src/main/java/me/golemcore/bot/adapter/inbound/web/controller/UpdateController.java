package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.UpdateConfirmRequest;
import me.golemcore.bot.adapter.inbound.web.dto.UpdateRollbackIntentRequest;
import me.golemcore.bot.adapter.inbound.web.dto.UpdateRollbackRequest;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateHistoryItem;
import me.golemcore.bot.domain.model.UpdateIntent;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.service.UpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/system/update")
@RequiredArgsConstructor
public class UpdateController {

    private final UpdateService updateService;

    @GetMapping("/status")
    public Mono<ResponseEntity<UpdateStatus>> getStatus() {
        return Mono.just(ResponseEntity.ok(updateService.getStatus()));
    }

    @PostMapping("/check")
    public Mono<ResponseEntity<UpdateActionResult>> check() {
        return Mono.just(ResponseEntity.ok(updateService.check()));
    }

    @PostMapping("/prepare")
    public Mono<ResponseEntity<UpdateActionResult>> prepare() {
        return Mono.just(ResponseEntity.ok(updateService.prepare()));
    }

    @PostMapping("/apply-intent")
    public Mono<ResponseEntity<UpdateIntent>> createApplyIntent() {
        return Mono.just(ResponseEntity.ok(updateService.createApplyIntent()));
    }

    @PostMapping("/apply")
    public Mono<ResponseEntity<UpdateActionResult>> apply(@RequestBody UpdateConfirmRequest request) {
        return Mono.just(ResponseEntity.ok(updateService.apply(request.getConfirmToken())));
    }

    @PostMapping("/rollback-intent")
    public Mono<ResponseEntity<UpdateIntent>> createRollbackIntent(
            @RequestBody(required = false) UpdateRollbackIntentRequest request) {
        String version = request != null ? request.getVersion() : null;
        return Mono.just(ResponseEntity.ok(updateService.createRollbackIntent(version)));
    }

    @PostMapping("/rollback")
    public Mono<ResponseEntity<UpdateActionResult>> rollback(@RequestBody UpdateRollbackRequest request) {
        return Mono.just(ResponseEntity.ok(updateService.rollback(request.getConfirmToken(), request.getVersion())));
    }

    @GetMapping("/history")
    public Mono<ResponseEntity<List<UpdateHistoryItem>>> history() {
        return Mono.just(ResponseEntity.ok(updateService.getHistory()));
    }
}
