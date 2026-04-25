package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.client.dto.HiveStatusResponse;
import me.golemcore.bot.client.mapper.HiveStatusResponseMapperSupport;
import me.golemcore.bot.port.outbound.HiveConnectionPort;
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

    private final HiveConnectionPort hiveConnectionService;

    public HiveController(HiveConnectionPort hiveConnectionService) {
        this.hiveConnectionService = hiveConnectionService;
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<HiveStatusResponse>> getStatus() {
        return Mono.just(ResponseEntity.ok(
                HiveStatusResponseMapperSupport.toResponse(hiveConnectionService.getStatus())));
    }

    @PostMapping("/join")
    public Mono<ResponseEntity<HiveStatusResponse>> join(
            @RequestBody(required = false) JoinHiveRequest request) {
        String joinCode = request != null ? request.joinCode() : null;
        return Mono.just(ResponseEntity.ok(
                HiveStatusResponseMapperSupport.toResponse(hiveConnectionService.join(joinCode))));
    }

    @PostMapping("/reconnect")
    public Mono<ResponseEntity<HiveStatusResponse>> reconnect() {
        return Mono.just(ResponseEntity.ok(
                HiveStatusResponseMapperSupport.toResponse(hiveConnectionService.reconnect())));
    }

    @PostMapping("/leave")
    public Mono<ResponseEntity<HiveStatusResponse>> leave() {
        return Mono.just(ResponseEntity.ok(
                HiveStatusResponseMapperSupport.toResponse(hiveConnectionService.leave())));
    }

    public record JoinHiveRequest(String joinCode) {
    }
}
