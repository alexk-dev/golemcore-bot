package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.port.inbound.CommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandsControllerTest {

    private CommandPort commandPort;
    private CommandsController controller;

    @BeforeEach
    void setUp() {
        commandPort = mock(CommandPort.class);
        controller = new CommandsController(commandPort);
    }

    @Test
    void shouldReturnCommandSpecs() {
        when(commandPort.listCommands()).thenReturn(List.of(
                new CommandPort.CommandDefinition("help", "Show help", "/help"),
                new CommandPort.CommandDefinition("plan", "Plan commands", "/plan <on|off|status>")));

        StepVerifier.create(controller.listCommands())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    var body = response.getBody();
                    assertNotNull(body);
                    assertEquals(2, body.size());
                    assertEquals("help", body.get(0).name());
                    assertEquals("/plan <on|off|status>", body.get(1).usage());
                })
                .verifyComplete();
    }
}
