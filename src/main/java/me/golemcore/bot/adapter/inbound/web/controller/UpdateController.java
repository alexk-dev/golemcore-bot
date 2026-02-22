package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.UpdateActionResult;
import me.golemcore.bot.domain.model.UpdateStatus;
import me.golemcore.bot.domain.service.UpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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

    @PostMapping("/update-now")
    public Mono<ResponseEntity<UpdateActionResult>> updateNow() {
        return Mono.just(ResponseEntity.ok(updateService.updateNow()));
    }
}
