package me.golemcore.bot.adapter.inbound.web.config;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.WebSocketChatHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * WebFlux WebSocket configuration for the dashboard chat endpoint.
 */
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    private final WebSocketChatHandler webSocketChatHandler;

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/chat", webSocketChatHandler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
