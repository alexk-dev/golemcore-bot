package me.golemcore.bot.adapter.outbound.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.infrastructure.i18n.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

class MessageLocalizationAdapterTest {

    private MessageService messageService;
    private MessageLocalizationAdapter adapter;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        adapter = new MessageLocalizationAdapter(messageService);
    }

    @Test
    void shouldExposeDefaultLanguageAndDelegateStateChanges() {
        adapter.setLanguage("ru");

        assertEquals(MessageService.DEFAULT_LANG, adapter.defaultLanguage());
        verify(messageService).setLanguage("ru");
    }

    @Test
    void shouldDelegateMessageLookup() {
        when(messageService.getMessage("greeting", "ru", "Alex")).thenReturn("Privet, Alex");

        String actual = adapter.getMessage("greeting", "ru", "Alex");

        assertEquals("Privet, Alex", actual);
        verify(messageService).getMessage("greeting", "ru", "Alex");
    }
}
