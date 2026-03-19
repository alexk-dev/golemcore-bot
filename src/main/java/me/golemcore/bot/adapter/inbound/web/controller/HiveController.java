package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.service.HiveConnectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/hive")
public class HiveController {

    private final HiveConnectionService hiveConnectionService;

    public HiveController(HiveConnectionService hiveConnectionService) {
        this.hiveConnectionService = hiveConnectionService;
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<HiveConnectionService.HiveStatusSnapshot>> getStatus() {
        return Mono.just(ResponseEntity.ok(hiveConnectionService.getStatus()));
    }

    @PostMapping("/join")
    public Mono<ResponseEntity<HiveConnectionService.HiveStatusSnapshot>> join(
            @RequestBody(required = false) JoinHiveRequest request) {
        String joinCode = request != null ? request.joinCode() : null;
        return Mono.just(ResponseEntity.ok(hiveConnectionService.join(joinCode)));
    }

    @PostMapping("/reconnect")
    public Mono<ResponseEntity<HiveConnectionService.HiveStatusSnapshot>> reconnect() {
        return Mono.just(ResponseEntity.ok(hiveConnectionService.reconnect()));
    }

    @PostMapping("/leave")
    public Mono<ResponseEntity<HiveConnectionService.HiveStatusSnapshot>> leave() {
        return Mono.just(ResponseEntity.ok(hiveConnectionService.leave()));
    }

    public record JoinHiveRequest(String joinCode) {
    }
}
