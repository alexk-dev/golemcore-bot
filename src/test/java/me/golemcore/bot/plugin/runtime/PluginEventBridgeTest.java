package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.domain.model.TelegramRestartEvent;
import me.golemcore.bot.plugin.runtime.extension.PluginExtensionApiMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PluginEventBridgeTest {

    @Test
    void shouldTranslatePayloadPlanReadyEvent() {
        PluginManager pluginManager = mock(PluginManager.class);
        PluginEventBridge bridge = new PluginEventBridge(pluginManager, new PluginExtensionApiMapper());
        PayloadApplicationEvent<PlanReadyEvent> event = new PayloadApplicationEvent<>(this,
                new PlanReadyEvent("plan-1", "chat-1"));

        bridge.onApplicationEvent(event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(pluginManager).publishToPlugins(captor.capture());
        Object published = captor.getValue();
        assertInstanceOf(me.golemcore.plugin.api.extension.model.PlanReadyEvent.class, published);
        me.golemcore.plugin.api.extension.model.PlanReadyEvent mapped = (me.golemcore.plugin.api.extension.model.PlanReadyEvent) published;
        assertEquals("plan-1", mapped.planId());
        assertEquals("chat-1", mapped.chatId());
    }

    @Test
    void shouldTranslatePlanExecutionCompletedEvent() {
        PluginManager pluginManager = mock(PluginManager.class);
        PluginEventBridge bridge = new PluginEventBridge(pluginManager, new PluginExtensionApiMapper());

        bridge.onApplicationEvent(new PayloadApplicationEvent<>(this,
                new PlanExecutionCompletedEvent("plan-1", "chat-1", "done")));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(pluginManager).publishToPlugins(captor.capture());
        Object published = captor.getValue();
        assertInstanceOf(me.golemcore.plugin.api.extension.model.PlanExecutionCompletedEvent.class, published);
    }

    @Test
    void shouldPassThroughPluginNativeEvents() {
        PluginManager pluginManager = mock(PluginManager.class);
        PluginEventBridge bridge = new PluginEventBridge(pluginManager, new PluginExtensionApiMapper());
        me.golemcore.plugin.api.extension.model.TelegramRestartEvent pluginEvent = new me.golemcore.plugin.api.extension.model.TelegramRestartEvent();

        bridge.onApplicationEvent(new PayloadApplicationEvent<>(this, pluginEvent));

        verify(pluginManager).publishToPlugins(pluginEvent);
    }

    @Test
    void shouldIgnoreChildContextEvents() {
        PluginManager pluginManager = mock(PluginManager.class);
        PluginEventBridge bridge = new PluginEventBridge(pluginManager, new PluginExtensionApiMapper());
        AnnotationConfigApplicationContext childContext = new AnnotationConfigApplicationContext();
        ApplicationEvent event = new ApplicationEvent(childContext) {
        };

        bridge.onApplicationEvent(event);

        verify(pluginManager, never()).publishToPlugins(org.mockito.ArgumentMatchers.any());
        childContext.close();
    }

    @Test
    void shouldIgnoreUnsupportedEvents() {
        PluginManager pluginManager = mock(PluginManager.class);
        PluginEventBridge bridge = new PluginEventBridge(pluginManager, new PluginExtensionApiMapper());

        bridge.onApplicationEvent(new PayloadApplicationEvent<>(this, new TelegramRestartEvent()));
        bridge.onApplicationEvent(new PayloadApplicationEvent<>(this, "plain-string"));

        verify(pluginManager, times(1)).publishToPlugins(org.mockito.ArgumentMatchers.any(
                me.golemcore.plugin.api.extension.model.TelegramRestartEvent.class));
    }
}
