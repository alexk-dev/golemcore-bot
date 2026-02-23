package me.golemcore.bot.plugin.builtin.telegram.inbound;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TelegramSessionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramMenuHandlerTest {

    private static final String CHAT_ID = "100";
    private static final Integer MSG_ID = 42;
    private static final String MENU_AUTO_CALLBACK = "menu:auto";

    private TelegramMenuHandler handler;
    private TelegramClient telegramClient;
    private UserPreferencesService preferencesService;
    private ModelSelectionService modelSelectionService;
    private AutoModeService autoModeService;
    private PlanService planService;
    private TelegramSessionService telegramSessionService;
    private CommandPort commandRouter;

    @BeforeEach
    void setUp() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.ChannelProperties telegramProps = new BotProperties.ChannelProperties();
        telegramProps.setEnabled(true);
        telegramProps.setToken("test-token");
        when(properties.getChannels()).thenReturn(Map.of("telegram", telegramProps));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTelegramEnabled()).thenReturn(true);
        when(runtimeConfigService.getTelegramToken()).thenReturn("test-token");

        preferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        autoModeService = mock(AutoModeService.class);
        planService = mock(PlanService.class);
        telegramSessionService = mock(TelegramSessionService.class);
        MessageService messageService = mock(MessageService.class);
        commandRouter = mock(CommandPort.class);
        telegramClient = mock(TelegramClient.class);

        handler = new TelegramMenuHandler(
                properties,
                runtimeConfigService,
                preferencesService,
                modelSelectionService,
                autoModeService,
                planService,
                telegramSessionService,
                messageService,
                new TestObjectProvider<>(commandRouter));
        handler.setTelegramClient(telegramClient);

        // Default stubs
        UserPreferences prefs = UserPreferences.builder().language("en").build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        when(preferencesService.getLanguage()).thenReturn("en");
        when(preferencesService.getMessage(any(String.class))).thenReturn("text");
        when(preferencesService.getMessage(any(String.class), any())).thenReturn("text");
        when(messageService.getLanguageDisplayName("en")).thenReturn("English");
        when(messageService.getLanguageDisplayName("ru")).thenReturn("Русский");
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/gpt-5.1", null));
        when(telegramSessionService.resolveActiveConversationKey(CHAT_ID)).thenReturn("conv-1");
        when(telegramSessionService.createAndActivateConversation(anyString())).thenReturn("conv-new");
        when(telegramSessionService.listRecentSessions(CHAT_ID, 5)).thenReturn(List.of(
                AgentSession.builder().id("telegram:conv-1").channelType("telegram").chatId("conv-1")
                        .messages(List.of()).build(),
                AgentSession.builder().id("telegram:conv-2").channelType("telegram").chatId("conv-2")
                        .messages(List.of()).build()));
    }

    // ==================== sendMainMenu ====================

    @Test
    void shouldSendMainMenuWithCurrentState() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        handler.sendMainMenu(CHAT_ID);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertEquals(CHAT_ID, sent.getChatId());
        assertEquals("HTML", sent.getParseMode());
        assertTrue(sent.getReplyMarkup() instanceof InlineKeyboardMarkup);

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertFalse(keyboard.getKeyboard().isEmpty());
    }

    // ==================== Tier sub-menu ====================

    @Test
    void shouldShowTierSubMenuOnCallback() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:tier");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        EditMessageText edit = captor.getValue();

        assertEquals(CHAT_ID, edit.getChatId());
        assertEquals(MSG_ID, edit.getMessageId());
        assertTrue(edit.getReplyMarkup() instanceof InlineKeyboardMarkup);
    }

    @Test
    void shouldHighlightCurrentTier() throws Exception {
        UserPreferences prefs = UserPreferences.builder().language("en").modelTier("smart").build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:tier");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = captor.getValue().getReplyMarkup();

        // Find smart button — should have checkmark
        InlineKeyboardButton smartBtn = findButton(keyboard, "menu:tier:smart");
        assertTrue(smartBtn != null, "Should have found smart tier button");
        assertTrue(smartBtn.getText().contains("\u2713"), "Smart tier button should have checkmark");

        InlineKeyboardButton balancedBtn = findButton(keyboard, "menu:tier:balanced");
        assertTrue(balancedBtn != null, "Should have found balanced tier button");
        assertFalse(balancedBtn.getText().contains("\u2713"), "Balanced tier button should not have checkmark");
    }

    @Test
    void shouldChangeTierAndRefresh() throws Exception {
        UserPreferences prefs = UserPreferences.builder().language("en").modelTier("balanced").build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:tier:smart");

        assertEquals("smart", prefs.getModelTier());
        verify(preferencesService).savePreferences(prefs);
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    @Test
    void shouldToggleForce() throws Exception {
        UserPreferences prefs = UserPreferences.builder().language("en").tierForce(false).build();
        when(preferencesService.getPreferences()).thenReturn(prefs);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:tier:force");

        assertTrue(prefs.isTierForce());
        verify(preferencesService).savePreferences(prefs);
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    // ==================== Language sub-menu ====================

    @Test
    void shouldShowLangSubMenu() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:lang");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = captor.getValue().getReplyMarkup();

        // Should have language buttons and back button
        assertTrue(hasButtonWithCallback(keyboard, "menu:main"),
                "Language sub-menu should have back button");
    }

    @Test
    void shouldChangeLangAndRefresh() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:lang:ru");

        verify(preferencesService).setLanguage("ru");
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    // ==================== New chat confirmation ====================

    @Test
    void shouldShowNewChatConfirmation() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:new");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = captor.getValue().getReplyMarkup();

        // Should have yes and cancel buttons
        assertTrue(hasButtonWithCallback(keyboard, "menu:new:yes"),
                "New chat dialog should have yes button");
        assertTrue(hasButtonWithCallback(keyboard, "menu:new:cancel"),
                "New chat dialog should have cancel button");
    }

    @Test
    void shouldResetOnConfirm() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:new:yes");

        verify(telegramSessionService).createAndActivateConversation(CHAT_ID);
        // Should update to main menu after reset
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    @Test
    void shouldCancelNewChat() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:new:cancel");

        // Should go back to main menu
        verify(telegramClient).execute(any(EditMessageText.class));
        verify(telegramSessionService, never()).createAndActivateConversation(anyString());
    }

    @Test
    void shouldShowSessionsMenuOnCallback() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = captor.getValue().getReplyMarkup();

        assertTrue(hasButtonWithCallback(keyboard, "menu:sessions:new"));
        assertTrue(hasButtonWithCallback(keyboard, "menu:sessions:back"));
    }

    @Test
    void shouldSwitchSessionFromSessionsMenu() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions");
        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:sw:1");

        verify(telegramSessionService).activateConversation(CHAT_ID, "conv-2");
    }

    @Test
    void shouldCreateSessionFromSessionsMenu() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:new");

        verify(telegramSessionService).createAndActivateConversation(CHAT_ID);
    }

    @Test
    void shouldIgnoreMalformedSessionsSwitchIndex() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions");
        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:sw:not-a-number");

        verify(telegramSessionService, never()).activateConversation(eq(CHAT_ID), anyString());
    }

    @Test
    void shouldIgnoreOutOfBoundsSessionsSwitchIndex() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions");
        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:sw:99");

        verify(telegramSessionService, never()).activateConversation(eq(CHAT_ID), anyString());
    }

    @Test
    void shouldGoBackToMainFromSessionsMenu() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:back");

        verify(telegramClient).execute(any(EditMessageText.class));
    }

    @Test
    void shouldDropCachedSessionIndexOnBackFromSessionsMenu() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions");
        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:back");
        handler.handleCallback(CHAT_ID, MSG_ID, "menu:sessions:sw:1");

        verify(telegramSessionService, never()).activateConversation(eq(CHAT_ID), anyString());
    }

    @Test
    void shouldSendSessionsMenuAsStandaloneMessage() throws Exception {
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        handler.sendSessionsMenu(CHAT_ID);

        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== Informational buttons ====================

    @Test
    void shouldSendStatusAsSeparateMessage() throws Exception {
        when(commandRouter.execute(eq("status"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandPort.CommandResult.success("Status info")));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:status");

        verify(commandRouter).execute(eq("status"), eq(List.of()), any());
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertEquals(CHAT_ID, captor.getValue().getChatId());
    }

    // ==================== Close ====================

    @Test
    void shouldDeleteMessageOnClose() throws Exception {
        when(telegramClient.execute(any(DeleteMessage.class))).thenReturn(true);

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:close");

        ArgumentCaptor<DeleteMessage> captor = ArgumentCaptor.forClass(DeleteMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertEquals(CHAT_ID, captor.getValue().getChatId());
        assertEquals(MSG_ID, captor.getValue().getMessageId());
    }

    @Test
    void shouldFallbackOnCloseFail() throws Exception {
        when(telegramClient.execute(any(DeleteMessage.class)))
                .thenThrow(new TelegramApiRequestException("Message can't be deleted"));
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:close");

        // Should have tried delete, then fallback to edit
        verify(telegramClient).execute(any(DeleteMessage.class));
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    // ==================== Auto mode toggle ====================

    @Test
    void shouldShowAutoRowWhenEnabled() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        handler.sendMainMenu(CHAT_ID);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();

        assertTrue(hasButtonWithCallback(keyboard, MENU_AUTO_CALLBACK),
                "Menu should show auto toggle when feature is enabled");
    }

    @Test
    void shouldHideAutoRowWhenDisabled() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        handler.sendMainMenu(CHAT_ID);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) captor.getValue().getReplyMarkup();

        assertFalse(hasButtonWithCallback(keyboard, MENU_AUTO_CALLBACK),
                "Menu should hide auto toggle when feature is disabled");
    }

    @Test
    void shouldToggleAutoModeOn() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(false);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, MENU_AUTO_CALLBACK);

        verify(autoModeService).enableAutoMode();
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    @Test
    void shouldToggleAutoModeOff() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, MENU_AUTO_CALLBACK);

        verify(autoModeService).disableAutoMode();
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    // ==================== Plan mode toggle ====================

    @Test
    void shouldTogglePlanModeOn() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(new SessionIdentity("telegram", "conv-1"))).thenReturn(false);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:plan");

        verify(planService).activatePlanMode(new SessionIdentity("telegram", "conv-1"), CHAT_ID, null);
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    @Test
    void shouldTogglePlanModeOff() throws Exception {
        when(planService.isFeatureEnabled()).thenReturn(true);
        when(planService.isPlanModeActive(new SessionIdentity("telegram", "conv-1"))).thenReturn(true);
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:plan");

        verify(planService).deactivatePlanMode(new SessionIdentity("telegram", "conv-1"));
        verify(telegramClient).execute(any(EditMessageText.class));
    }

    // ==================== Non-menu callback ====================

    @Test
    void shouldReturnFalseForNonMenuCallback() {
        boolean handled = handler.handleCallback(CHAT_ID, MSG_ID, "confirm:abc:yes");

        assertFalse(handled, "Should return false for non-menu callback");
    }

    @Test
    void shouldReturnTrueForMenuCallback() throws Exception {
        stubEdit();

        boolean handled = handler.handleCallback(CHAT_ID, MSG_ID, "menu:main");

        assertTrue(handled, "Should return true for menu callback");
    }

    // ==================== Back to main menu ====================

    @Test
    void shouldReturnToMainMenuOnBackCallback() throws Exception {
        stubEdit();

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:main");

        ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
        verify(telegramClient).execute(captor.capture());
        EditMessageText edit = captor.getValue();

        assertEquals(CHAT_ID, edit.getChatId());
        assertEquals(MSG_ID, edit.getMessageId());
        assertTrue(edit.getReplyMarkup() instanceof InlineKeyboardMarkup);
    }

    // ==================== Reasoning display ====================

    @Test
    void shouldShowReasoningInMainMenu() throws Exception {
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("openai/o3", "medium"));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        // Stub to return the actual key+args for reasoning
        when(preferencesService.getMessage(eq("menu.tier.reasoning"), any(), any(), any()))
                .thenReturn("Tier: balanced → openai/o3 (reasoning: medium)");

        handler.sendMainMenu(CHAT_ID);

        verify(telegramClient).execute(any(SendMessage.class));
        verify(preferencesService).getMessage(eq("menu.tier.reasoning"), any(), any(), any());
    }

    // ==================== Compact command ====================

    @Test
    void shouldExecuteCompactViaSeparateMessage() throws Exception {
        when(commandRouter.execute(eq("compact"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandPort.CommandResult.success("Compacted")));
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(mock(org.telegram.telegrambots.meta.api.objects.message.Message.class));

        handler.handleCallback(CHAT_ID, MSG_ID, "menu:compact");

        verify(commandRouter).execute(eq("compact"), eq(List.of()), any());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    // ==================== Helpers ====================

    private void stubEdit() throws TelegramApiException {
        when(telegramClient.execute(any(EditMessageText.class)))
                .thenReturn(mock(Serializable.class));
    }

    private boolean hasButtonWithCallback(InlineKeyboardMarkup keyboard, String callbackData) {
        return findButton(keyboard, callbackData) != null;
    }

    private InlineKeyboardButton findButton(InlineKeyboardMarkup keyboard, String callbackData) {
        for (List<InlineKeyboardButton> row : keyboard.getKeyboard()) {
            for (InlineKeyboardButton btn : row) {
                if (callbackData.equals(btn.getCallbackData())) {
                    return btn;
                }
            }
        }
        return null;
    }
}
