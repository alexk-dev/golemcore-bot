package me.golemcore.bot.plugin.runtime;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.plugin.runtime.extension.PluginExtensionApiMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.stereotype.Component;

/**
 * Republishes parent-context events into plugin child contexts.
 */
@Component
@RequiredArgsConstructor
public class PluginEventBridge implements ApplicationListener<ApplicationEvent> {

    private final PluginManager pluginManager;
    private final PluginExtensionApiMapper pluginApiMapper;

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event == null) {
            return;
        }
        Object source = event.getSource();
        if (source instanceof ApplicationContext) {
            String sourceClassName = source.getClass().getName();
            if (sourceClassName.contains("AnnotationConfigApplicationContext")) {
                return;
            }
        }
        Object candidate = event instanceof PayloadApplicationEvent<?> payloadEvent ? payloadEvent.getPayload() : event;
        Object translated = translate(candidate);
        if (translated != null) {
            pluginManager.publishToPlugins(translated);
        }
    }

    private Object translate(Object event) {
        if (event instanceof me.golemcore.bot.domain.model.PlanReadyEvent planReadyEvent) {
            return pluginApiMapper.toPluginPlanReadyEvent(planReadyEvent);
        }
        if (event instanceof me.golemcore.bot.domain.model.PlanExecutionCompletedEvent planExecutionCompletedEvent) {
            return pluginApiMapper.toPluginPlanExecutionCompletedEvent(planExecutionCompletedEvent);
        }
        if (event instanceof me.golemcore.bot.domain.model.TelegramRestartEvent telegramRestartEvent) {
            return pluginApiMapper.toPluginTelegramRestartEvent(telegramRestartEvent);
        }
        if (event instanceof me.golemcore.plugin.api.extension.model.PlanReadyEvent
                || event instanceof me.golemcore.plugin.api.extension.model.PlanExecutionCompletedEvent
                || event instanceof me.golemcore.plugin.api.extension.model.TelegramRestartEvent) {
            return event;
        }
        return null;
    }
}
