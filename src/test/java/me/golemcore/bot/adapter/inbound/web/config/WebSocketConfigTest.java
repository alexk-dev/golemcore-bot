package me.golemcore.bot.adapter.inbound.web.config;

import me.golemcore.bot.adapter.inbound.web.WebSocketChatHandler;
import me.golemcore.bot.adapter.inbound.web.WebSocketLogsHandler;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class WebSocketConfigTest {

    @Test
    void shouldMapChatAndLogsWebSocketEndpoints() throws Exception {
        WebSocketChatHandler chatHandler = mock(WebSocketChatHandler.class);
        WebSocketLogsHandler logsHandler = mock(WebSocketLogsHandler.class);
        WebSocketConfig config = new WebSocketConfig(chatHandler, logsHandler, createBotProperties(80 * 1024 * 1024));

        HandlerMapping mapping = config.webSocketHandlerMapping();
        assertNotNull(mapping);
        assertEquals(SimpleUrlHandlerMapping.class, mapping.getClass());

        SimpleUrlHandlerMapping urlMapping = (SimpleUrlHandlerMapping) mapping;
        assertEquals(-1, urlMapping.getOrder());
        Map<String, ?> handlers = urlMapping.getUrlMap();
        assertEquals(chatHandler, handlers.get("/ws/chat"));
        assertEquals(logsHandler, handlers.get("/ws/logs"));
    }

    @Test
    void shouldCreateWebSocketHandlerAdapterBean() {
        int expectedLimit = 80 * 1024 * 1024;
        WebSocketConfig config = new WebSocketConfig(mock(WebSocketChatHandler.class),
                mock(WebSocketLogsHandler.class),
                createBotProperties(expectedLimit));

        WebSocketHandlerAdapter adapter = config.webSocketHandlerAdapter();

        assertNotNull(adapter);
        assertEquals(HandshakeWebSocketService.class, adapter.getWebSocketService().getClass());

        HandshakeWebSocketService service = (HandshakeWebSocketService) adapter.getWebSocketService();
        ReactorNettyRequestUpgradeStrategy upgradeStrategy = (ReactorNettyRequestUpgradeStrategy) service
                .getUpgradeStrategy();
        WebsocketServerSpec spec = upgradeStrategy.getWebsocketServerSpec();
        assertEquals(expectedLimit, spec.maxFramePayloadLength());
    }

    private static BotProperties createBotProperties(int webSocketMaxFramePayloadLength) {
        BotProperties properties = new BotProperties();
        properties.getDashboard().setWebSocketMaxFramePayloadLength(webSocketMaxFramePayloadLength);
        return properties;
    }
}
