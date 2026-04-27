package me.golemcore.bot.adapter.outbound.i18n;

import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.outbound.LocalizationPort;
import org.springframework.stereotype.Component;

/**
 * Outbound localization adapter over {@link MessageService}.
 */
@Component
public class MessageLocalizationAdapter implements LocalizationPort {

    private final MessageService messageService;

    public MessageLocalizationAdapter(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public String defaultLanguage() {
        return MessageService.DEFAULT_LANG;
    }

    @Override
    public void setLanguage(String language) {
        messageService.setLanguage(language);
    }

    @Override
    public String getMessage(String key, String language, Object... args) {
        return messageService.getMessage(key, language, args);
    }
}
