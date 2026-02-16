package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/commands")
@RequiredArgsConstructor
public class CommandsController {

    private final CommandPort commandPort;

    @GetMapping
    public Mono<ResponseEntity<List<CommandSpecDto>>> listCommands() {
        List<CommandSpecDto> result = commandPort.listCommands().stream()
                .map(c -> new CommandSpecDto(c.name(), c.description(), c.usage()))
                .toList();
        return Mono.just(ResponseEntity.ok(result));
    }

    public record CommandSpecDto(
            String name,
            String description,
            String usage) {
    }
}
