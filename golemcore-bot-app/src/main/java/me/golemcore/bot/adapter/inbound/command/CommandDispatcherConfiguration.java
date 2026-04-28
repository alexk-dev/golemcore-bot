package me.golemcore.bot.adapter.inbound.command;

import java.util.List;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CommandDispatcherConfiguration {

    @Bean
    CommandDispatcher commandDispatcher(List<CommandHandler> handlers, UserPreferencesService preferencesService) {
        return new CommandDispatcher(handlers, preferencesService);
    }
}
