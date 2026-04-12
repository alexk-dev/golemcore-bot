package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.application.update.UpdateService;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/system/update")
@RequiredArgsConstructor
public class UpdateController {

    private final UpdateService updateService;

    @GetMapping("/status")
    public Mono<ResponseEntity<UpdateStatus>> getStatus() {
        return Mono.fromCallable(() -> ResponseEntity.ok(updateService.getStatus()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/config")
    public Mono<ResponseEntity<RuntimeConfig.UpdateConfig>> getConfig() {
        return Mono.fromCallable(() -> ResponseEntity.ok(updateService.getConfig()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/check")
    public Mono<ResponseEntity<UpdateActionResult>> check() {
        return Mono.fromCallable(() -> ResponseEntity.ok(updateService.check()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/update-now")
    public Mono<ResponseEntity<UpdateActionResult>> updateNow() {
        return Mono.fromCallable(() -> ResponseEntity.ok(updateService.updateNow()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/config")
    public Mono<ResponseEntity<RuntimeConfig.UpdateConfig>> updateConfig(
            @RequestBody RuntimeConfig.UpdateConfig config) {
        return Mono.fromCallable(() -> ResponseEntity.ok(updateService.updateConfig(config)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
