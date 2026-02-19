package me.golemcore.bot.adapter.inbound.web.config;

import me.golemcore.bot.adapter.inbound.web.WebSocketChatHandler;
import me.golemcore.bot.adapter.inbound.web.WebSocketLogsHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class WebSocketConfigTest {

    @Test
    void shouldMapChatAndLogsWebSocketEndpoints() throws Exception {
        WebSocketChatHandler chatHandler = mock(WebSocketChatHandler.class);
        WebSocketLogsHandler logsHandler = mock(WebSocketLogsHandler.class);
        WebSocketConfig config = new WebSocketConfig(chatHandler, logsHandler);

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
        WebSocketConfig config = new WebSocketConfig(mock(WebSocketChatHandler.class),
                mock(WebSocketLogsHandler.class));

        WebSocketHandlerAdapter adapter = config.webSocketHandlerAdapter();

        assertNotNull(adapter);
    }
}
