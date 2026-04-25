package me.golemcore.bot.adapter.inbound.web.config;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.WebSocketChatHandler;
import me.golemcore.bot.adapter.inbound.web.WebSocketLogsHandler;
import me.golemcore.bot.adapter.inbound.web.terminal.TerminalWebSocketHandler;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

/**
 * WebFlux WebSocket configuration for the dashboard chat endpoint.
 */
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    private final WebSocketChatHandler webSocketChatHandler;
    private final WebSocketLogsHandler webSocketLogsHandler;
    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final BotProperties botProperties;

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(
                "/ws/chat", webSocketChatHandler,
                "/ws/logs", webSocketLogsHandler,
                "/ws/terminal", terminalWebSocketHandler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        int maxFramePayloadLength = botProperties.getDashboard().getWebSocketMaxFramePayloadLength();
        HandshakeWebSocketService service = new HandshakeWebSocketService(
                new ReactorNettyRequestUpgradeStrategy(
                        () -> WebsocketServerSpec.builder().maxFramePayloadLength(maxFramePayloadLength)));
        return new WebSocketHandlerAdapter(service);
    }
}
