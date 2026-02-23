/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.adapter.inbound.telegram;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.TelegramSessionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles the /menu command and all menu:* callback queries.
 *
 * <p>
 * Provides a centralized inline-keyboard menu for Telegram with:
 * <ul>
 * <li>Main menu with current state overview</li>
 * <li>Tier sub-menu with tier selection and force lock toggle</li>
 * <li>Language sub-menu</li>
 * <li>New chat confirmation dialog</li>
 * <li>Informational buttons (status, skills, tools, help) via CommandPort</li>
 * <li>Auto/Plan mode toggles (when features are enabled)</li>
 * </ul>
 */
@Component
@Slf4j
@SuppressWarnings("PMD.LooseCoupling") // InlineKeyboardRow is required by Telegram API, no interface available
public class TelegramMenuHandler {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CALLBACK_PREFIX = "menu:";
    private static final String ON = "ON";
    private static final String OFF = "OFF";
    private static final String TIER_BALANCED = "balanced";
    private static final String TIER_SMART = "smart";
    private static final String TIER_CODING = "coding";
    private static final String TIER_DEEP = "deep";
    private static final String ACTION_FORCE = "force";
    private static final String ACTION_YES = "yes";
    private static final String ACTION_NEW = "new";
    private static final String ACTION_BACK = "back";
    private static final String HTML_BOLD_OPEN = "<b>";
    private static final String HTML_BOLD_CLOSE_NL = "</b>\n\n";
    private static final int RECENT_SESSIONS_LIMIT = 5;
    private static final int SESSION_TITLE_MAX_LEN = 22;
    private static final int SESSION_INDEX_CACHE_MAX_ENTRIES = 1024;
    private static final Set<String> VALID_TIERS = Set.of(TIER_BALANCED, TIER_SMART, TIER_CODING, TIER_DEEP);

    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final AutoModeService autoModeService;
    private final PlanService planService;
    private final TelegramSessionService telegramSessionService;
    private final MessageService messageService;
    private final ObjectProvider<CommandPort> commandRouter;

    private final AtomicReference<TelegramClient> telegramClient = new AtomicReference<>();
    private final Map<String, List<String>> sessionIndexCache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > SESSION_INDEX_CACHE_MAX_ENTRIES;
        }
    });

    public TelegramMenuHandler(
            BotProperties properties,
            RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            AutoModeService autoModeService,
            PlanService planService,
            TelegramSessionService telegramSessionService,
            MessageService messageService,
            ObjectProvider<CommandPort> commandRouter) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.preferencesService = preferencesService;
        this.modelSelectionService = modelSelectionService;
        this.autoModeService = autoModeService;
        this.planService = planService;
        this.telegramSessionService = telegramSessionService;
        this.messageService = messageService;
        this.commandRouter = commandRouter;
    }

    /**
     * Package-private setter for testing.
     */
    void setTelegramClient(TelegramClient client) {
        this.telegramClient.set(client);
    }

    private TelegramClient getOrCreateClient() {
        TelegramClient client = this.telegramClient.get();
        if (client != null) {
            return client;
        }
        if (!runtimeConfigService.isTelegramEnabled()) {
            return null;
        }
        String token = runtimeConfigService.getTelegramToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        OkHttpTelegramClient newClient = new OkHttpTelegramClient(token);
        this.telegramClient.compareAndSet(null, newClient);
        return this.telegramClient.get();
    }

    // ==================== Public API ====================

    /**
     * Send the main menu as a new message. Called from /menu and /settings
     * commands.
     */
    void sendMainMenu(String chatId) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            log.warn("[Menu] TelegramClient not available");
            return;
        }
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(buildMainMenuText())
                    .parseMode("HTML")
                    .replyMarkup(buildMainMenuKeyboard())
                    .build();
            client.execute(message);
            log.debug("[Menu] Sent main menu to chat: {}", chatId);
        } catch (Exception e) {
            log.error("[Menu] Failed to send main menu", e);
        }
    }

    /**
     * Send sessions menu as a standalone message. Called from /sessions command.
     */
    void sendSessionsMenu(String chatId) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            log.warn("[Menu] TelegramClient not available");
            return;
        }
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(buildSessionsMenuText(chatId))
                    .parseMode("HTML")
                    .replyMarkup(buildSessionsMenuKeyboard(chatId))
                    .build();
            client.execute(message);
            log.debug("[Menu] Sent sessions menu to chat: {}", chatId);
        } catch (Exception e) {
            log.error("[Menu] Failed to send sessions menu", e);
        }
    }

    /**
     * Handle a menu:* callback query. Returns true if the callback was handled.
     */
    boolean handleCallback(String chatId, Integer messageId, String data) {
        if (!data.startsWith(CALLBACK_PREFIX)) {
            return false;
        }

        String payload = data.substring(CALLBACK_PREFIX.length());
        int colonIdx = payload.indexOf(':');
        String section = colonIdx >= 0 ? payload.substring(0, colonIdx) : payload;
        String action = colonIdx >= 0 ? payload.substring(colonIdx + 1) : null;

        dispatchSection(chatId, messageId, section, action);
        return true;
    }

    private void dispatchSection(String chatId, Integer messageId, String section, String action) {
        switch (section) {
        case "main":
            updateToMainMenu(chatId, messageId);
            break;
        case "tier":
            handleTierCallback(chatId, messageId, action);
            break;
        case "lang":
            handleLangCallback(chatId, messageId, action);
            break;
        case "new":
            handleNewChatCallback(chatId, messageId, action);
            break;
        case "sessions":
            handleSessionsCallback(chatId, messageId, action);
            break;
        case "status", "skills", "tools", "help", "compact":
            executeAndSendSeparate(chatId, section);
            break;
        case "auto":
            handleAutoToggle(chatId, messageId);
            break;
        case "plan":
            handlePlanToggle(chatId, messageId);
            break;
        case "close":
            handleClose(chatId, messageId);
            break;
        default:
            log.debug("[Menu] Unknown menu section: {}", section);
            break;
        }
    }

    // ==================== Menu screens ====================

    private String buildMainMenuText() {
        UserPreferences prefs = preferencesService.getPreferences();
        String tier = prefs.getModelTier() != null ? prefs.getModelTier() : TIER_BALANCED;
        ModelSelectionService.ModelSelection selection = modelSelectionService.resolveForTier(tier);
        String model = selection.model() != null ? selection.model() : "-";
        String langName = messageService.getLanguageDisplayName(preferencesService.getLanguage());

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.title")).append(HTML_BOLD_CLOSE_NL);

        if (selection.reasoning() != null) {
            sb.append(msg("menu.tier.reasoning", tier, model, selection.reasoning()));
        } else {
            sb.append(msg("menu.tier", tier, model));
        }
        sb.append("\n");
        sb.append(msg("menu.language", langName));

        return sb.toString();
    }

    private InlineKeyboardMarkup buildMainMenuKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(row(
                button(msg("menu.btn.tier"), "menu:tier"),
                button(msg("menu.btn.lang"), "menu:lang")));

        rows.add(row(
                button(msg("menu.btn.status"), "menu:status"),
                button(msg("menu.btn.skills"), "menu:skills"),
                button(msg("menu.btn.tools"), "menu:tools")));

        rows.add(row(
                button(msg("menu.btn.new"), "menu:new"),
                button(msg("menu.btn.sessions"), "menu:sessions"),
                button(msg("menu.btn.compact"), "menu:compact")));

        if (autoModeService.isFeatureEnabled()) {
            String autoStatus = autoModeService.isAutoModeEnabled() ? ON : OFF;
            rows.add(row(button(msg("menu.btn.auto", autoStatus), "menu:auto")));
        }

        if (planService.isFeatureEnabled()) {
            String planStatus = planService.isPlanModeActive() ? ON : OFF;
            rows.add(row(button(msg("menu.btn.plan", planStatus), "menu:plan")));
        }

        rows.add(row(
                button(msg("menu.btn.help"), "menu:help"),
                button(msg("menu.btn.close"), "menu:close")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String buildTierMenuText() {
        UserPreferences prefs = preferencesService.getPreferences();
        String tier = prefs.getModelTier() != null ? prefs.getModelTier() : TIER_BALANCED;
        ModelSelectionService.ModelSelection selection = modelSelectionService.resolveForTier(tier);
        String model = selection.model() != null ? selection.model() : "-";
        String forceStatus = prefs.isTierForce() ? ON : OFF;

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.tier.title")).append(HTML_BOLD_CLOSE_NL);

        if (selection.reasoning() != null) {
            sb.append(msg("menu.tier.current.reasoning", tier, model, selection.reasoning()));
        } else {
            sb.append(msg("menu.tier.current", tier, model));
        }
        sb.append("\n");
        sb.append(msg("menu.tier.force", forceStatus));

        return sb.toString();
    }

    private InlineKeyboardMarkup buildTierMenuKeyboard() {
        UserPreferences prefs = preferencesService.getPreferences();
        String currentTier = prefs.getModelTier() != null ? prefs.getModelTier() : TIER_BALANCED;
        boolean force = prefs.isTierForce();

        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(row(
                tierButton(TIER_BALANCED, currentTier),
                tierButton(TIER_SMART, currentTier)));

        rows.add(row(
                tierButton(TIER_CODING, currentTier),
                tierButton(TIER_DEEP, currentTier)));

        String forceLabel = msg("menu.tier.force.btn", force ? ON : OFF);
        rows.add(row(button(forceLabel, "menu:tier:force")));

        rows.add(row(button(msg("menu.btn.back"), "menu:main")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardButton tierButton(String tier, String currentTier) {
        String icon = tierIcon(tier);
        String label = tier.equals(currentTier) ? icon + " " + tier + " \u2713" : icon + " " + tier;
        return button(label, "menu:tier:" + tier);
    }

    private String tierIcon(String tier) {
        return switch (tier) {
        case TIER_BALANCED -> "\u2696\ufe0f";
        case TIER_SMART -> "\ud83e\udde0";
        case TIER_CODING -> "\ud83d\udcbb";
        case TIER_DEEP -> "\ud83d\udd2c";
        default -> "";
        };
    }

    private String buildLangMenuText() {
        String langName = messageService.getLanguageDisplayName(preferencesService.getLanguage());
        return HTML_BOLD_OPEN + msg("menu.lang.title") + HTML_BOLD_CLOSE_NL + msg("menu.lang.current", langName);
    }

    private InlineKeyboardMarkup buildLangMenuKeyboard() {
        String currentLang = preferencesService.getLanguage();
        String enLabel = "en".equals(currentLang) ? "English \u2713" : "English";
        String ruLabel = "ru".equals(currentLang) ? "\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u2713"
                : "\u0420\u0443\u0441\u0441\u043a\u0438\u0439";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(
                button(enLabel, "menu:lang:en"),
                button(ruLabel, "menu:lang:ru")));
        rows.add(row(button(msg("menu.btn.back"), "menu:main")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String buildNewConfirmText() {
        return HTML_BOLD_OPEN + msg("menu.new.title") + HTML_BOLD_CLOSE_NL + msg("menu.new.warning");
    }

    private InlineKeyboardMarkup buildNewConfirmKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(
                button(msg("menu.new.yes"), "menu:new:yes"),
                button(msg("menu.new.cancel"), "menu:new:cancel")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String buildSessionsMenuText(String chatId) {
        String activeConversationKey = telegramSessionService.resolveActiveConversationKey(chatId);
        List<AgentSession> recentSessions = telegramSessionService.listRecentSessions(chatId, RECENT_SESSIONS_LIMIT);

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.sessions.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.sessions.current", escapeHtml(shortConversationKey(activeConversationKey))));

        if (recentSessions.isEmpty()) {
            sb.append("\n\n").append(msg("menu.sessions.empty"));
            return sb.toString();
        }

        sb.append("\n\n");
        for (int index = 0; index < recentSessions.size(); index++) {
            AgentSession session = recentSessions.get(index);
            String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
            String prefix = conversationKey.equals(activeConversationKey) ? "✅ " : "";
            sb.append(index + 1)
                    .append(". ")
                    .append(prefix)
                    .append(escapeHtml(resolveSessionTitle(session)))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private InlineKeyboardMarkup buildSessionsMenuKeyboard(String chatId) {
        String activeConversationKey = telegramSessionService.resolveActiveConversationKey(chatId);
        List<AgentSession> recentSessions = telegramSessionService.listRecentSessions(chatId, RECENT_SESSIONS_LIMIT);

        List<String> indexToConversation = new ArrayList<>();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (AgentSession session : recentSessions) {
            String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
            if (conversationKey == null || conversationKey.isBlank()) {
                continue;
            }
            int nextIndex = indexToConversation.size();
            indexToConversation.add(conversationKey);
            String label = buildSwitchButtonLabel(session, conversationKey.equals(activeConversationKey));
            rows.add(row(button(label, "menu:sessions:sw:" + nextIndex)));
        }

        sessionIndexCache.put(chatId, List.copyOf(indexToConversation));

        rows.add(row(
                button(msg("menu.sessions.new"), "menu:sessions:new"),
                button(msg("menu.btn.back"), "menu:sessions:back")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    // ==================== Callback handlers ====================

    private void updateToMainMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildMainMenuText(), buildMainMenuKeyboard());
    }

    private void handleTierCallback(String chatId, Integer messageId, String action) {
        if (action == null) {
            editMessage(chatId, messageId, buildTierMenuText(), buildTierMenuKeyboard());
            return;
        }

        if (ACTION_FORCE.equals(action)) {
            UserPreferences prefs = preferencesService.getPreferences();
            prefs.setTierForce(!prefs.isTierForce());
            preferencesService.savePreferences(prefs);
            editMessage(chatId, messageId, buildTierMenuText(), buildTierMenuKeyboard());
            return;
        }

        if (VALID_TIERS.contains(action)) {
            UserPreferences prefs = preferencesService.getPreferences();
            prefs.setModelTier(action);
            preferencesService.savePreferences(prefs);
            editMessage(chatId, messageId, buildTierMenuText(), buildTierMenuKeyboard());
        }
    }

    private void handleLangCallback(String chatId, Integer messageId, String action) {
        if (action == null) {
            editMessage(chatId, messageId, buildLangMenuText(), buildLangMenuKeyboard());
            return;
        }

        if ("en".equals(action) || "ru".equals(action)) {
            preferencesService.setLanguage(action);
            editMessage(chatId, messageId, buildLangMenuText(), buildLangMenuKeyboard());
        }
    }

    private void handleNewChatCallback(String chatId, Integer messageId, String action) {
        if (action == null) {
            editMessage(chatId, messageId, buildNewConfirmText(), buildNewConfirmKeyboard());
            return;
        }

        if (ACTION_YES.equals(action)) {
            telegramSessionService.createAndActivateConversation(chatId);
        }
        // Both yes (after reset) and cancel → back to main menu
        updateToMainMenu(chatId, messageId);
    }

    private void handleSessionsCallback(String chatId, Integer messageId, String action) {
        if (action == null) {
            editMessage(chatId, messageId, buildSessionsMenuText(chatId), buildSessionsMenuKeyboard(chatId));
            return;
        }

        if (ACTION_NEW.equals(action)) {
            telegramSessionService.createAndActivateConversation(chatId);
            editMessage(chatId, messageId, buildSessionsMenuText(chatId), buildSessionsMenuKeyboard(chatId));
            return;
        }

        if (ACTION_BACK.equals(action)) {
            sessionIndexCache.remove(chatId);
            updateToMainMenu(chatId, messageId);
            return;
        }

        Integer switchIndex = parseSwitchIndex(action);
        if (switchIndex != null) {
            List<String> recentIndex = sessionIndexCache.get(chatId);
            if (recentIndex != null && switchIndex >= 0 && switchIndex < recentIndex.size()) {
                String conversationKey = recentIndex.get(switchIndex);
                telegramSessionService.activateConversation(chatId, conversationKey);
            }
            editMessage(chatId, messageId, buildSessionsMenuText(chatId), buildSessionsMenuKeyboard(chatId));
            return;
        }

        editMessage(chatId, messageId, buildSessionsMenuText(chatId), buildSessionsMenuKeyboard(chatId));
    }

    private void executeAndSendSeparate(String chatId, String command) {
        CommandPort router = commandRouter.getIfAvailable();
        if (router == null) {
            return;
        }
        String activeConversationKey = telegramSessionService.resolveActiveConversationKey(chatId);
        String sessionId = CHANNEL_TYPE + ":" + activeConversationKey;
        try {
            CommandPort.CommandResult result = router.execute(command, List.of(), Map.of(
                    "sessionId", sessionId,
                    "chatId", activeConversationKey,
                    "sessionChatId", activeConversationKey,
                    "transportChatId", chatId,
                    "conversationKey", activeConversationKey,
                    "channelType", CHANNEL_TYPE)).join();
            sendSeparateMessage(chatId, result.output());
        } catch (Exception e) {
            log.error("[Menu] Failed to execute /{}", command, e);
        }
    }

    private void handleAutoToggle(String chatId, Integer messageId) {
        if (!autoModeService.isFeatureEnabled()) {
            return;
        }
        if (autoModeService.isAutoModeEnabled()) {
            autoModeService.disableAutoMode();
        } else {
            autoModeService.enableAutoMode();
        }
        updateToMainMenu(chatId, messageId);
    }

    private void handlePlanToggle(String chatId, Integer messageId) {
        if (!planService.isFeatureEnabled()) {
            return;
        }
        if (planService.isPlanModeActive()) {
            planService.deactivatePlanMode();
        } else {
            planService.activatePlanMode(chatId, null);
        }
        updateToMainMenu(chatId, messageId);
    }

    private void handleClose(String chatId, Integer messageId) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            return;
        }
        try {
            DeleteMessage delete = DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build();
            client.execute(delete);
        } catch (Exception e) {
            log.debug("[Menu] Cannot delete message, clearing keyboard instead", e);
            try {
                EditMessageText edit = EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(msg("menu.title"))
                        .build();
                client.execute(edit);
            } catch (Exception ex) {
                log.error("[Menu] Failed to clear menu message", ex);
            }
        }
    }

    // ==================== Helpers ====================

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    private void editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            return;
        }
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build();
            client.execute(edit);
        } catch (Exception e) {
            log.error("[Menu] Failed to edit message", e);
        }
    }

    private void sendSeparateMessage(String chatId, String text) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            return;
        }
        try {
            String formatted = TelegramHtmlFormatter.format(text);
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(formatted)
                    .parseMode("HTML")
                    .build();
            client.execute(message);
        } catch (Exception e) {
            log.error("[Menu] Failed to send separate message", e);
        }
    }

    private Integer parseSwitchIndex(String action) {
        if (action == null || !action.startsWith("sw:")) {
            return null;
        }
        try {
            return Integer.parseInt(action.substring("sw:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildSwitchButtonLabel(AgentSession session, boolean active) {
        String title = truncate(resolveSessionTitle(session), SESSION_TITLE_MAX_LEN);
        if (active) {
            return "✅ " + title;
        }
        return title;
    }

    private String resolveSessionTitle(AgentSession session) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return msg(
                    "menu.sessions.fallback",
                    shortConversationKey(SessionIdentitySupport.resolveConversationKey(session)));
        }

        for (me.golemcore.bot.domain.model.Message message : session.getMessages()) {
            if (message == null || !"user".equals(message.getRole())) {
                continue;
            }
            if (message.getContent() != null && !message.getContent().isBlank()) {
                return message.getContent().trim();
            }
        }
        return msg(
                "menu.sessions.fallback",
                shortConversationKey(SessionIdentitySupport.resolveConversationKey(session)));
    }

    private String shortConversationKey(String conversationKey) {
        if (conversationKey == null || conversationKey.isBlank()) {
            return "-";
        }
        if (conversationKey.length() <= 12) {
            return conversationKey;
        }
        return conversationKey.substring(0, 12);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
