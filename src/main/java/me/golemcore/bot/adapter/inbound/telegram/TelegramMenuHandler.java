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
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.TelegramSessionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.i18n.MessageService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int MAX_GOAL_BUTTONS = 6;
    private static final int MAX_TASK_BUTTONS = 8;
    private static final int MAX_SCHEDULE_BUTTONS = 8;
    private static final int MAX_PLAN_BUTTONS = 6;
    private static final int MAX_TELEGRAM_CALLBACK_DATA_LENGTH = 64;
    private static final String DEFAULT_DAILY_CRON = "0 0 9 * * *";
    private static final DateTimeFormatter SCHEDULE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneOffset.UTC);
    private static final Set<String> VALID_TIERS = Set.of(TIER_BALANCED, TIER_SMART, TIER_CODING, TIER_DEEP);
    private static final Set<Integer> WEEKDAY_SET = Set.of(1, 2, 3, 4, 5, 6, 7);

    private final BotProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService preferencesService;
    private final ModelSelectionService modelSelectionService;
    private final AutoModeService autoModeService;
    private final PlanService planService;
    private final ScheduleService scheduleService;
    private final TelegramSessionService telegramSessionService;
    private final MessageService messageService;
    private final ObjectProvider<CommandPort> commandRouter;

    private final AtomicReference<TelegramClient> telegramClient = new AtomicReference<>();
    private final Map<String, List<String>> sessionIndexCache = Collections
            .synchronizedMap(new SessionIndexCache(SESSION_INDEX_CACHE_MAX_ENTRIES));
    private final Map<String, List<String>> goalIndexCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> taskIndexCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> scheduleIndexCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> goalPageByChat = new ConcurrentHashMap<>();
    private final Map<String, Integer> taskPageByChat = new ConcurrentHashMap<>();
    private final Map<String, Integer> schedulePageByChat = new ConcurrentHashMap<>();
    private final Map<String, Integer> planPageByChat = new ConcurrentHashMap<>();
    private final Map<String, List<String>> planIndexCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> persistentMenuByChat = new ConcurrentHashMap<>();
    private final Map<String, Integer> persistentMenuMessageIdByChat = new ConcurrentHashMap<>();
    private final Map<String, PendingInputState> pendingInputByChat = new ConcurrentHashMap<>();
    private final Map<String, ScheduleDraft> scheduleDraftByChat = new ConcurrentHashMap<>();

    public TelegramMenuHandler(
            BotProperties properties,
            RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            AutoModeService autoModeService,
            PlanService planService,
            ScheduleService scheduleService,
            TelegramSessionService telegramSessionService,
            MessageService messageService,
            ObjectProvider<CommandPort> commandRouter) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.preferencesService = preferencesService;
        this.modelSelectionService = modelSelectionService;
        this.autoModeService = autoModeService;
        this.planService = planService;
        this.scheduleService = scheduleService;
        this.telegramSessionService = telegramSessionService;
        this.messageService = messageService;
        this.commandRouter = commandRouter;
    }

    private static final class SessionIndexCache extends LinkedHashMap<String, List<String>> {
        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        private SessionIndexCache(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > maxEntries;
        }
    }

    private static final class TaskContext {
        private final Goal goal;
        private final AutoTask task;

        private TaskContext(Goal goal, AutoTask task) {
            this.goal = goal;
            this.task = task;
        }
    }

    private static final class PlanContext {
        private final Plan plan;

        private PlanContext(Plan plan) {
            this.plan = plan;
        }
    }

    private enum PendingInputType {
        CREATE_GOAL, CREATE_STANDALONE_TASK, SCHEDULE_CUSTOM_TIME, SCHEDULE_CUSTOM_LIMIT
    }

    private static final class PendingInputState {
        private final PendingInputType type;

        private PendingInputState(PendingInputType type) {
            this.type = type;
        }
    }

    private static final class TimeValue {
        private final int hour;
        private final int minute;

        private TimeValue(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }
    }

    private enum ScheduleWizardStep {
        FREQUENCY, DAYS, TIME, LIMIT, CONFIRM
    }

    private enum ScheduleFrequency {
        DAILY, WEEKDAYS, WEEKLY, CUSTOM_DAYS
    }

    private enum ScheduleReturnTarget {
        GOAL_DETAIL, TASK_DETAIL, SCHEDULES_LIST
    }

    private static final class ScheduleDraft {
        private ScheduleEntry.ScheduleType type;
        private String targetId;
        private String targetLabel;
        private ScheduleFrequency frequency;
        private Set<Integer> days;
        private int hour;
        private int minute;
        private int maxExecutions;
        private ScheduleWizardStep step;
        private ScheduleReturnTarget returnTarget;
        private int returnIndex;

        private ScheduleDraft(ScheduleEntry.ScheduleType type, String targetId, String targetLabel,
                ScheduleReturnTarget returnTarget, int returnIndex) {
            this.type = type;
            this.targetId = targetId;
            this.targetLabel = targetLabel;
            this.returnTarget = returnTarget;
            this.returnIndex = returnIndex;
            this.frequency = ScheduleFrequency.DAILY;
            this.days = new HashSet<>(Set.of(1, 2, 3, 4, 5));
            this.hour = 9;
            this.minute = 0;
            this.maxExecutions = -1;
            this.step = ScheduleWizardStep.FREQUENCY;
        }
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
                    .text(buildMainMenuText(chatId))
                    .parseMode("HTML")
                    .replyMarkup(buildMainMenuKeyboard(chatId))
                    .build();
            Message sentMessage = client.execute(message);
            persistentMenuByChat.put(chatId, true);
            if (sentMessage != null && sentMessage.getMessageId() != null) {
                persistentMenuMessageIdByChat.put(chatId, sentMessage.getMessageId());
            }
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
     * Re-send the main menu after command execution when persistent menu mode is
     * enabled for this chat.
     */
    void resendPersistentMenuIfEnabled(String chatId) {
        if (!isPersistentMenuEnabled(chatId)) {
            return;
        }

        Integer messageId = persistentMenuMessageIdByChat.get(chatId);
        if (messageId != null) {
            boolean updated = editMessage(chatId, messageId, buildMainMenuText(chatId), buildMainMenuKeyboard(chatId));
            if (updated) {
                return;
            }
            log.warn("[Menu] Failed to refresh persistent menu via edit, sending a new menu message. chatId={}",
                    chatId);
        }

        sendMainMenu(chatId);
    }

    boolean handlePendingInput(String chatId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.trim();
        if ("/cancel".equalsIgnoreCase(normalized)
                || "\u043e\u0442\u043c\u0435\u043d\u0430".equalsIgnoreCase(normalized)) {
            pendingInputByChat.remove(chatId);
            scheduleDraftByChat.remove(chatId);
            sendSeparateMessage(chatId, msg("menu.input.cancelled"));
            resendPersistentMenuIfEnabled(chatId);
            return true;
        }

        PendingInputState pendingInputState = pendingInputByChat.get(chatId);
        if (pendingInputState == null) {
            return false;
        }

        switch (pendingInputState.type) {
        case CREATE_GOAL:
            pendingInputByChat.remove(chatId);
            try {
                Goal goal = autoModeService.createGoal(normalized, null);
                sendSeparateMessage(chatId, msg("menu.auto.goal.created", escapeHtml(goal.getTitle())));
            } catch (Exception e) {
                log.warn("[Menu] Failed to create goal from menu input", e);
                sendSeparateMessage(chatId, msg("menu.auto.goal.create-failed"));
            }
            resendPersistentMenuIfEnabled(chatId);
            return true;
        case CREATE_STANDALONE_TASK:
            pendingInputByChat.remove(chatId);
            try {
                AutoTask task = autoModeService.addStandaloneTask(normalized, null);
                sendSeparateMessage(chatId, msg("menu.auto.task.created.standalone", escapeHtml(task.getTitle())));
            } catch (Exception e) {
                log.warn("[Menu] Failed to create standalone task from menu input", e);
                sendSeparateMessage(chatId, msg("menu.auto.task.create-failed"));
            }
            resendPersistentMenuIfEnabled(chatId);
            return true;
        case SCHEDULE_CUSTOM_TIME:
            return handlePendingScheduleCustomTime(chatId, normalized);
        case SCHEDULE_CUSTOM_LIMIT:
            return handlePendingScheduleCustomLimit(chatId, normalized);
        default:
            pendingInputByChat.remove(chatId);
            return false;
        }
    }

    private boolean isPersistentMenuEnabled(String chatId) {
        return Boolean.TRUE.equals(persistentMenuByChat.get(chatId));
    }

    /**
     * Handle a menu:* callback query. Returns true if the callback was handled.
     */
    boolean handleCallback(String chatId, Integer messageId, String data) {
        if (!data.startsWith(CALLBACK_PREFIX)) {
            return false;
        }

        if (messageId != null) {
            persistentMenuByChat.put(chatId, true);
            persistentMenuMessageIdByChat.put(chatId, messageId);
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
        case "autoMenu":
            handleAutoMenuCallback(chatId, messageId, action);
            break;
        case "planMenu":
            handlePlanMenuCallback(chatId, messageId, action);
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

    private String buildMainMenuText(String chatId) {
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

    private InlineKeyboardMarkup buildMainMenuKeyboard(String chatId) {
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
            SessionIdentity sessionIdentity = resolveTelegramSessionIdentity(chatId);
            String planStatus = planService.isPlanModeActive(sessionIdentity) ? ON : OFF;
            rows.add(row(button(msg("menu.btn.plan", planStatus), "menu:plan")));
        }

        if (autoModeService.isFeatureEnabled() || planService.isFeatureEnabled()) {
            List<InlineKeyboardButton> manageButtons = new ArrayList<>();
            if (autoModeService.isFeatureEnabled()) {
                manageButtons.add(button(msg("menu.btn.auto.manage"), "menu:autoMenu"));
            }
            if (planService.isFeatureEnabled()) {
                manageButtons.add(button(msg("menu.btn.plan.manage"), "menu:planMenu"));
            }
            rows.add(new InlineKeyboardRow(manageButtons));
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

    // ==================== Auto mode menu ====================

    private void handleAutoMenuCallback(String chatId, Integer messageId, String action) {
        if (!autoModeService.isFeatureEnabled()) {
            updateToMainMenu(chatId, messageId);
            return;
        }

        if (action == null || action.isBlank() || "refresh".equals(action)) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        if ("noop".equals(action)) {
            return;
        }

        if (ACTION_BACK.equals(action)) {
            clearAutoMenuCaches(chatId);
            updateToMainMenu(chatId, messageId);
            return;
        }

        if ("goals".equals(action)) {
            goalPageByChat.put(chatId, 0);
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        if ("tasks".equals(action)) {
            taskPageByChat.put(chatId, 0);
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        if ("schedules".equals(action)) {
            schedulePageByChat.put(chatId, 0);
            updateToAutoSchedulesMenu(chatId, messageId);
            return;
        }

        if ("goalsPrev".equals(action)) {
            adjustPage(goalPageByChat, chatId, -1);
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        if ("goalsNext".equals(action)) {
            adjustPage(goalPageByChat, chatId, 1);
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        if ("tasksPrev".equals(action)) {
            adjustPage(taskPageByChat, chatId, -1);
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        if ("tasksNext".equals(action)) {
            adjustPage(taskPageByChat, chatId, 1);
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        if ("schedulesPrev".equals(action)) {
            adjustPage(schedulePageByChat, chatId, -1);
            updateToAutoSchedulesMenu(chatId, messageId);
            return;
        }

        if ("schedulesNext".equals(action)) {
            adjustPage(schedulePageByChat, chatId, 1);
            updateToAutoSchedulesMenu(chatId, messageId);
            return;
        }

        if ("createGoal".equals(action)) {
            pendingInputByChat.put(chatId, new PendingInputState(PendingInputType.CREATE_GOAL));
            sendSeparateMessage(chatId, msg("menu.auto.create-goal.hint"));
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        if ("createStandaloneTask".equals(action)) {
            pendingInputByChat.put(chatId, new PendingInputState(PendingInputType.CREATE_STANDALONE_TASK));
            sendSeparateMessage(chatId, msg("menu.auto.create-task.hint"));
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        if ("schCancel".equals(action)) {
            cancelScheduleWizard(chatId, messageId);
            return;
        }

        if ("schBack".equals(action)) {
            handleScheduleWizardBack(chatId, messageId);
            return;
        }

        if (action.startsWith("schFreq:")) {
            handleScheduleWizardFrequency(chatId, messageId, action.substring("schFreq:".length()));
            return;
        }

        Integer dayToggle = parseIndexAction(action, "schDay:");
        if (dayToggle != null) {
            handleScheduleWizardToggleDay(chatId, messageId, dayToggle);
            return;
        }

        if ("schDaysDone".equals(action)) {
            handleScheduleWizardDaysDone(chatId, messageId);
            return;
        }

        if (action.startsWith("schTime:")) {
            handleScheduleWizardTime(chatId, messageId, action.substring("schTime:".length()));
            return;
        }

        if ("schTimeCustom".equals(action)) {
            startScheduleCustomTimeInput(chatId);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.time.custom.prompt"));
            renderScheduleWizardByChat(chatId, messageId);
            return;
        }

        if (action.startsWith("schLimit:")) {
            handleScheduleWizardLimit(chatId, messageId, action.substring("schLimit:".length()));
            return;
        }

        if ("schLimitCustom".equals(action)) {
            startScheduleCustomLimitInput(chatId);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.limit.custom.prompt"));
            renderScheduleWizardByChat(chatId, messageId);
            return;
        }

        if ("schSave".equals(action)) {
            handleScheduleWizardSave(chatId, messageId);
            return;
        }

        Integer goalDetailIndex = parseIndexAction(action, "goal:");
        if (goalDetailIndex != null) {
            showGoalDetail(chatId, messageId, goalDetailIndex);
            return;
        }

        Integer goalDoneIndex = parseIndexAction(action, "goalDone:");
        if (goalDoneIndex != null) {
            handleGoalComplete(chatId, messageId, goalDoneIndex);
            return;
        }

        Integer goalDeleteConfirmIndex = parseIndexAction(action, "goalDeleteConfirm:");
        if (goalDeleteConfirmIndex != null) {
            showGoalDeleteConfirm(chatId, messageId, goalDeleteConfirmIndex);
            return;
        }

        Integer goalDeleteIndex = parseIndexAction(action, "goalDelete:");
        if (goalDeleteIndex != null) {
            handleGoalDelete(chatId, messageId, goalDeleteIndex);
            return;
        }

        Integer goalDailyIndex = parseIndexAction(action, "goalDaily:");
        if (goalDailyIndex != null) {
            handleGoalDailySchedule(chatId, messageId, goalDailyIndex);
            return;
        }

        Integer goalScheduleIndex = parseIndexAction(action, "goalSchedule:");
        if (goalScheduleIndex != null) {
            startGoalScheduleWizard(chatId, messageId, goalScheduleIndex);
            return;
        }

        Integer taskDetailIndex = parseIndexAction(action, "task:");
        if (taskDetailIndex != null) {
            showTaskDetail(chatId, messageId, taskDetailIndex);
            return;
        }

        Integer taskDeleteConfirmIndex = parseIndexAction(action, "taskDeleteConfirm:");
        if (taskDeleteConfirmIndex != null) {
            showTaskDeleteConfirm(chatId, messageId, taskDeleteConfirmIndex);
            return;
        }

        Integer taskDeleteIndex = parseIndexAction(action, "taskDelete:");
        if (taskDeleteIndex != null) {
            handleTaskDelete(chatId, messageId, taskDeleteIndex);
            return;
        }

        Integer taskDailyIndex = parseIndexAction(action, "taskDaily:");
        if (taskDailyIndex != null) {
            handleTaskDailySchedule(chatId, messageId, taskDailyIndex);
            return;
        }

        Integer taskScheduleIndex = parseIndexAction(action, "taskSchedule:");
        if (taskScheduleIndex != null) {
            startTaskScheduleWizard(chatId, messageId, taskScheduleIndex);
            return;
        }

        if (action.startsWith("taskSet:")) {
            handleTaskStatusUpdate(chatId, messageId, action);
            return;
        }

        Integer scheduleDeleteConfirmIndex = parseIndexAction(action, "scheduleDelConfirm:");
        if (scheduleDeleteConfirmIndex != null) {
            showScheduleDeleteConfirm(chatId, messageId, scheduleDeleteConfirmIndex);
            return;
        }

        Integer scheduleDeleteIndex = parseIndexAction(action, "scheduleDel:");
        if (scheduleDeleteIndex != null) {
            handleScheduleDelete(chatId, messageId, scheduleDeleteIndex);
            return;
        }

        updateToAutoMenu(chatId, messageId);
    }

    private void updateToAutoMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildAutoMenuText(), buildAutoMenuKeyboard());
    }

    private void updateToAutoGoalsMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildAutoGoalsText(chatId), buildAutoGoalsKeyboard(chatId));
    }

    private void updateToAutoTasksMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildAutoTasksText(chatId), buildAutoTasksKeyboard(chatId));
    }

    private void updateToAutoSchedulesMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildAutoSchedulesText(chatId), buildAutoSchedulesKeyboard(chatId));
    }

    private String buildAutoMenuText() {
        List<Goal> goals = getDisplayGoals();
        long activeGoals = goals.stream().filter(goal -> goal.getStatus() == Goal.GoalStatus.ACTIVE).count();
        int totalTasks = goals.stream().mapToInt(goal -> goal.getTasks().size()).sum();
        long pendingTasks = goals.stream()
                .flatMap(goal -> goal.getTasks().stream())
                .filter(task -> task.getStatus() == AutoTask.TaskStatus.PENDING
                        || task.getStatus() == AutoTask.TaskStatus.IN_PROGRESS)
                .count();
        int schedules = scheduleService.getSchedules().size();

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.auto.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.status", autoModeService.isAutoModeEnabled() ? ON : OFF)).append("\n");
        sb.append(msg("menu.auto.goals", activeGoals, goals.size())).append("\n");
        sb.append(msg("menu.auto.tasks", pendingTasks, totalTasks)).append("\n");
        sb.append(msg("menu.auto.schedules", schedules));
        return sb.toString();
    }

    private InlineKeyboardMarkup buildAutoMenuKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(
                button(msg("menu.auto.btn.goals"), "menu:autoMenu:goals"),
                button(msg("menu.auto.btn.tasks"), "menu:autoMenu:tasks")));
        rows.add(row(button(msg("menu.auto.btn.schedules"), "menu:autoMenu:schedules")));
        rows.add(row(
                button(msg("menu.auto.btn.create-goal"), "menu:autoMenu:createGoal"),
                button(msg("menu.auto.btn.create-task"), "menu:autoMenu:createStandaloneTask")));
        rows.add(row(
                button(msg("menu.auto.btn.refresh"), "menu:autoMenu:refresh"),
                button(msg("menu.btn.back"), "menu:autoMenu:back")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String buildAutoGoalsText(String chatId) {
        List<Goal> goals = getDisplayGoals();
        if (goals.isEmpty()) {
            goalIndexCache.remove(chatId);
            goalPageByChat.remove(chatId);
            return HTML_BOLD_OPEN + msg("menu.auto.goals.title") + HTML_BOLD_CLOSE_NL + msg("menu.auto.goals.empty");
        }

        int page = normalizePage(goalPageByChat, chatId, goals.size(), MAX_GOAL_BUTTONS);
        int start = page * MAX_GOAL_BUTTONS;
        int end = Math.min(start + MAX_GOAL_BUTTONS, goals.size());

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.auto.goals.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.page", page + 1, totalPages(goals.size(), MAX_GOAL_BUTTONS))).append("\n\n");

        for (int index = start; index < end; index++) {
            Goal goal = goals.get(index);
            long completedTasks = goal.getCompletedTaskCount();
            int totalTasks = goal.getTasks().size();
            sb.append(index + 1)
                    .append(". ")
                    .append(goalStatusIcon(goal))
                    .append(" ")
                    .append(escapeHtml(truncate(goal.getTitle(), SESSION_TITLE_MAX_LEN + 8)))
                    .append(" (")
                    .append(completedTasks)
                    .append("/")
                    .append(totalTasks)
                    .append(")")
                    .append("\n");
        }

        return sb.toString().trim();
    }

    private InlineKeyboardMarkup buildAutoGoalsKeyboard(String chatId) {
        List<Goal> goals = getDisplayGoals();
        List<String> goalIds = new ArrayList<>();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        int page = normalizePage(goalPageByChat, chatId, goals.size(), MAX_GOAL_BUTTONS);
        int start = page * MAX_GOAL_BUTTONS;
        int end = Math.min(start + MAX_GOAL_BUTTONS, goals.size());

        for (int index = start; index < end; index++) {
            Goal goal = goals.get(index);
            goalIds.add(goal.getId());
            int pageIndex = index - start;
            String callbackData = "menu:autoMenu:goal:" + pageIndex;
            String label = goalStatusIcon(goal) + " " + truncate(goal.getTitle(), 18);
            rows.add(row(button(label, callbackData)));
        }

        goalIndexCache.put(chatId, List.copyOf(goalIds));
        rows.add(buildPaginationRow(page, goals.size(), MAX_GOAL_BUTTONS, "menu:autoMenu:goalsPrev",
                "menu:autoMenu:goalsNext"));
        rows.add(row(button(msg("menu.auto.btn.create-goal"), "menu:autoMenu:createGoal")));
        rows.add(row(button(msg("menu.auto.btn.refresh"), "menu:autoMenu:goals")));
        rows.add(row(button(msg("menu.btn.back"), "menu:autoMenu:back")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void showGoalDetail(String chatId, Integer messageId, int goalIndex) {
        Optional<Goal> goalOptional = resolveGoalByIndex(chatId, goalIndex);
        if (goalOptional.isEmpty()) {
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        Goal goal = goalOptional.get();
        editMessage(chatId, messageId, buildGoalDetailText(goal), buildGoalDetailKeyboard(goalIndex, goal));
    }

    private String buildGoalDetailText(Goal goal) {
        long completedTasks = goal.getCompletedTaskCount();
        int totalTasks = goal.getTasks().size();
        List<ScheduleEntry> schedules = scheduleService.findSchedulesForTarget(goal.getId());

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN)
                .append(msg("menu.auto.goal.title", escapeHtml(goal.getTitle())))
                .append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.goal.status", localizedGoalStatus(goal.getStatus()))).append("\n");
        sb.append(msg("menu.auto.goal.tasks", completedTasks, totalTasks)).append("\n");
        sb.append(msg("menu.auto.goal.schedules", schedules.size())).append("\n");
        sb.append(msg("menu.auto.goal.id", shortId(goal.getId())));

        if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
            sb.append("\n\n").append(escapeHtml(goal.getDescription()));
        }

        if (!schedules.isEmpty()) {
            sb.append("\n\n").append(msg("menu.auto.schedule.preview.title"));
            int previewCount = Math.min(3, schedules.size());
            for (int index = 0; index < previewCount; index++) {
                sb.append("\n")
                        .append("? ")
                        .append(formatScheduleSummary(schedules.get(index), goal.getTitle()));
            }
        }

        return sb.toString();
    }

    private InlineKeyboardMarkup buildGoalDetailKeyboard(int goalIndex, Goal goal) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (goal.getStatus() == Goal.GoalStatus.ACTIVE) {
            rows.add(row(button(msg("menu.auto.goal.btn.complete"), "menu:autoMenu:goalDone:" + goalIndex)));
        }

        rows.add(row(
                button(msg("menu.auto.goal.btn.schedule-daily"), "menu:autoMenu:goalDaily:" + goalIndex),
                button(msg("menu.auto.goal.btn.schedule-custom"), "menu:autoMenu:goalSchedule:" + goalIndex)));
        rows.add(row(button(msg("menu.auto.goal.btn.delete.confirm"), "menu:autoMenu:goalDeleteConfirm:" + goalIndex)));
        rows.add(row(button(msg("menu.btn.back"), "menu:autoMenu:goals")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void handleGoalComplete(String chatId, Integer messageId, int goalIndex) {
        Optional<Goal> goalOptional = resolveGoalByIndex(chatId, goalIndex);
        if (goalOptional.isEmpty()) {
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        Goal goal = goalOptional.get();
        if (goal.getStatus() == Goal.GoalStatus.ACTIVE) {
            try {
                autoModeService.completeGoal(goal.getId());
                sendSeparateMessage(chatId, msg("menu.auto.goal.done", escapeHtml(goal.getTitle())));
            } catch (Exception e) {
                log.warn("[Menu] Failed to complete goal {}", goal.getId(), e);
            }
        }

        updateToAutoGoalsMenu(chatId, messageId);
    }

    private void showGoalDeleteConfirm(String chatId, Integer messageId, int goalIndex) {
        Optional<Goal> goalOptional = resolveGoalByIndex(chatId, goalIndex);
        if (goalOptional.isEmpty()) {
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        Goal goal = goalOptional.get();
        String text = HTML_BOLD_OPEN + msg("menu.auto.confirm.title") + HTML_BOLD_CLOSE_NL
                + msg("menu.auto.confirm.goal.delete", escapeHtml(truncate(goal.getTitle(), 30)));
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(List.of(
                row(button(msg("menu.auto.confirm.yes"), "menu:autoMenu:goalDelete:" + goalIndex),
                        button(msg("menu.auto.confirm.no"), "menu:autoMenu:goal:" + goalIndex))))
                .build();
        editMessage(chatId, messageId, text, keyboard);
    }

    private void handleGoalDelete(String chatId, Integer messageId, int goalIndex) {
        Optional<Goal> goalOptional = resolveGoalByIndex(chatId, goalIndex);
        if (goalOptional.isEmpty()) {
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        Goal goal = goalOptional.get();
        try {
            autoModeService.deleteGoal(goal.getId());
            sendSeparateMessage(chatId, msg("menu.auto.goal.deleted", escapeHtml(goal.getTitle())));
        } catch (Exception e) {
            log.warn("[Menu] Failed to delete goal {}", goal.getId(), e);
        }

        updateToAutoGoalsMenu(chatId, messageId);
    }

    private void handleGoalDailySchedule(String chatId, Integer messageId, int goalIndex) {
        Optional<Goal> goalOptional = resolveGoalByIndex(chatId, goalIndex);
        if (goalOptional.isEmpty()) {
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        Goal goal = goalOptional.get();
        try {
            ScheduleEntry entry = scheduleService.createSchedule(
                    ScheduleEntry.ScheduleType.GOAL,
                    goal.getId(),
                    DEFAULT_DAILY_CRON,
                    -1);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.created", entry.getId(), entry.getCronExpression(),
                    describeScheduleLimit(entry)));
        } catch (Exception e) {
            log.warn("[Menu] Failed to create daily goal schedule for {}", goal.getId(), e);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.create-failed"));
        }

        showGoalDetail(chatId, messageId, goalIndex);
    }

    private String buildAutoTasksText(String chatId) {
        List<TaskContext> contexts = buildTaskContexts();
        if (contexts.isEmpty()) {
            taskIndexCache.remove(chatId);
            taskPageByChat.remove(chatId);
            return HTML_BOLD_OPEN + msg("menu.auto.tasks.title") + HTML_BOLD_CLOSE_NL + msg("menu.auto.tasks.empty");
        }

        int page = normalizePage(taskPageByChat, chatId, contexts.size(), MAX_TASK_BUTTONS);
        int start = page * MAX_TASK_BUTTONS;
        int end = Math.min(start + MAX_TASK_BUTTONS, contexts.size());

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.auto.tasks.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.page", page + 1, totalPages(contexts.size(), MAX_TASK_BUTTONS))).append("\n\n");

        for (int index = start; index < end; index++) {
            TaskContext context = contexts.get(index);
            sb.append(index + 1)
                    .append(". ")
                    .append(taskStatusIcon(context.task))
                    .append(" ")
                    .append(escapeHtml(truncate(context.task.getTitle(), 20)))
                    .append(" \u2014 ")
                    .append(escapeHtml(truncate(renderTaskGroupTitle(context.goal), 14)))
                    .append("\n");
        }

        return sb.toString().trim();
    }

    private InlineKeyboardMarkup buildAutoTasksKeyboard(String chatId) {
        List<TaskContext> contexts = buildTaskContexts();
        List<String> taskKeys = new ArrayList<>();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        int page = normalizePage(taskPageByChat, chatId, contexts.size(), MAX_TASK_BUTTONS);
        int start = page * MAX_TASK_BUTTONS;
        int end = Math.min(start + MAX_TASK_BUTTONS, contexts.size());

        for (int index = start; index < end; index++) {
            TaskContext context = contexts.get(index);
            taskKeys.add(buildTaskKey(context.goal.getId(), context.task.getId()));
            int pageIndex = index - start;
            String label = taskStatusIcon(context.task) + " " + truncate(context.task.getTitle(), 18);
            rows.add(row(button(label, "menu:autoMenu:task:" + pageIndex)));
        }

        taskIndexCache.put(chatId, List.copyOf(taskKeys));
        rows.add(buildPaginationRow(page, contexts.size(), MAX_TASK_BUTTONS, "menu:autoMenu:tasksPrev",
                "menu:autoMenu:tasksNext"));
        rows.add(row(button(msg("menu.auto.btn.create-task"), "menu:autoMenu:createStandaloneTask")));
        rows.add(row(button(msg("menu.auto.btn.refresh"), "menu:autoMenu:tasks")));
        rows.add(row(button(msg("menu.btn.back"), "menu:autoMenu:back")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void showTaskDetail(String chatId, Integer messageId, int taskIndex) {
        Optional<TaskContext> contextOptional = resolveTaskContextByIndex(chatId, taskIndex);
        if (contextOptional.isEmpty()) {
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        TaskContext context = contextOptional.get();
        editMessage(chatId, messageId, buildTaskDetailText(context), buildTaskDetailKeyboard(taskIndex));
    }

    private String buildTaskDetailText(TaskContext context) {
        List<ScheduleEntry> schedules = scheduleService.findSchedulesForTarget(context.task.getId());

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN)
                .append(msg("menu.auto.task.title", escapeHtml(context.task.getTitle())))
                .append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.task.goal", escapeHtml(renderTaskGroupTitle(context.goal)))).append("\n");
        sb.append(msg("menu.auto.task.status", localizedTaskStatus(context.task.getStatus()))).append("\n");
        sb.append(msg("menu.auto.task.schedules", schedules.size())).append("\n");
        sb.append(msg("menu.auto.task.id", shortId(context.task.getId())));

        if (context.task.getDescription() != null && !context.task.getDescription().isBlank()) {
            sb.append("\n\n").append(escapeHtml(context.task.getDescription()));
        }

        if (context.task.getResult() != null && !context.task.getResult().isBlank()) {
            sb.append("\n\n").append(msg("menu.auto.task.result", escapeHtml(context.task.getResult())));
        }

        if (!schedules.isEmpty()) {
            sb.append("\n\n").append(msg("menu.auto.schedule.preview.title"));
            int previewCount = Math.min(3, schedules.size());
            for (int index = 0; index < previewCount; index++) {
                sb.append("\n")
                        .append("? ")
                        .append(formatScheduleSummary(schedules.get(index), context.task.getTitle()));
            }
        }

        return sb.toString();
    }

    private InlineKeyboardMarkup buildTaskDetailKeyboard(int taskIndex) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(row(
                button(msg("menu.auto.task.btn.in-progress"), "menu:autoMenu:taskSet:" + taskIndex + ":ip"),
                button(msg("menu.auto.task.btn.done"), "menu:autoMenu:taskSet:" + taskIndex + ":done")));
        rows.add(row(
                button(msg("menu.auto.task.btn.failed"), "menu:autoMenu:taskSet:" + taskIndex + ":fail"),
                button(msg("menu.auto.task.btn.skipped"), "menu:autoMenu:taskSet:" + taskIndex + ":skip")));
        rows.add(row(button(msg("menu.auto.task.btn.pending"), "menu:autoMenu:taskSet:" + taskIndex + ":pending")));
        rows.add(row(
                button(msg("menu.auto.task.btn.schedule-daily"), "menu:autoMenu:taskDaily:" + taskIndex),
                button(msg("menu.auto.task.btn.schedule-custom"), "menu:autoMenu:taskSchedule:" + taskIndex)));
        rows.add(row(button(msg("menu.auto.task.btn.delete.confirm"), "menu:autoMenu:taskDeleteConfirm:" + taskIndex)));
        rows.add(row(button(msg("menu.btn.back"), "menu:autoMenu:tasks")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void handleTaskStatusUpdate(String chatId, Integer messageId, String action) {
        String payload = action.substring("taskSet:".length());
        int separatorIndex = payload.indexOf(':');
        if (separatorIndex <= 0 || separatorIndex >= payload.length() - 1) {
            showTaskUpdateFailure(chatId, messageId);
            return;
        }

        String indexPart = payload.substring(0, separatorIndex);
        String statusPart = payload.substring(separatorIndex + 1);

        int taskIndex;
        try {
            taskIndex = Integer.parseInt(indexPart);
        } catch (NumberFormatException e) {
            showTaskUpdateFailure(chatId, messageId);
            return;
        }

        Optional<TaskContext> contextOptional = resolveTaskContextByIndex(chatId, taskIndex);
        if (contextOptional.isEmpty()) {
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        Optional<AutoTask.TaskStatus> statusOptional = parseTaskStatus(statusPart);
        if (statusOptional.isEmpty()) {
            showTaskUpdateFailure(chatId, messageId);
            return;
        }

        TaskContext context = contextOptional.get();
        try {
            autoModeService.updateTaskStatus(
                    context.goal.getId(),
                    context.task.getId(),
                    statusOptional.get(),
                    null);
            sendSeparateMessage(chatId, msg("menu.auto.task.updated", escapeHtml(context.task.getTitle()),
                    localizedTaskStatus(statusOptional.get())));
        } catch (Exception e) {
            log.warn("[Menu] Failed to update task {}", context.task.getId(), e);
            sendSeparateMessage(chatId, msg("menu.auto.task.update-failed"));
        }

        showTaskDetail(chatId, messageId, taskIndex);
    }

    private void showTaskDeleteConfirm(String chatId, Integer messageId, int taskIndex) {
        Optional<TaskContext> contextOptional = resolveTaskContextByIndex(chatId, taskIndex);
        if (contextOptional.isEmpty()) {
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        TaskContext context = contextOptional.get();
        String text = HTML_BOLD_OPEN + msg("menu.auto.confirm.title") + HTML_BOLD_CLOSE_NL
                + msg("menu.auto.confirm.task.delete", escapeHtml(truncate(context.task.getTitle(), 30)));
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(List.of(
                row(button(msg("menu.auto.confirm.yes"), "menu:autoMenu:taskDelete:" + taskIndex),
                        button(msg("menu.auto.confirm.no"), "menu:autoMenu:task:" + taskIndex))))
                .build();
        editMessage(chatId, messageId, text, keyboard);
    }

    private void handleTaskDelete(String chatId, Integer messageId, int taskIndex) {
        Optional<TaskContext> contextOptional = resolveTaskContextByIndex(chatId, taskIndex);
        if (contextOptional.isEmpty()) {
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        TaskContext context = contextOptional.get();
        try {
            autoModeService.deleteTask(context.goal.getId(), context.task.getId());
            sendSeparateMessage(chatId, msg("menu.auto.task.deleted", escapeHtml(context.task.getTitle())));
        } catch (Exception e) {
            log.warn("[Menu] Failed to delete task {}", context.task.getId(), e);
            sendSeparateMessage(chatId, msg("menu.auto.task.delete-failed"));
        }

        updateToAutoTasksMenu(chatId, messageId);
    }

    private void handleTaskDailySchedule(String chatId, Integer messageId, int taskIndex) {
        Optional<TaskContext> contextOptional = resolveTaskContextByIndex(chatId, taskIndex);
        if (contextOptional.isEmpty()) {
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        TaskContext context = contextOptional.get();
        try {
            ScheduleEntry entry = scheduleService.createSchedule(
                    ScheduleEntry.ScheduleType.TASK,
                    context.task.getId(),
                    DEFAULT_DAILY_CRON,
                    -1);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.created", entry.getId(), entry.getCronExpression(),
                    describeScheduleLimit(entry)));
        } catch (Exception e) {
            log.warn("[Menu] Failed to create daily task schedule for {}", context.task.getId(), e);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.create-failed"));
        }

        showTaskDetail(chatId, messageId, taskIndex);
    }

    private String buildAutoSchedulesText(String chatId) {
        List<ScheduleEntry> schedules = scheduleService.getSchedules();
        if (schedules.isEmpty()) {
            scheduleIndexCache.remove(chatId);
            schedulePageByChat.remove(chatId);
            return HTML_BOLD_OPEN + msg("menu.auto.schedules.title") + HTML_BOLD_CLOSE_NL
                    + msg("menu.auto.schedules.empty");
        }

        int page = normalizePage(schedulePageByChat, chatId, schedules.size(), MAX_SCHEDULE_BUTTONS);
        int start = page * MAX_SCHEDULE_BUTTONS;
        int end = Math.min(start + MAX_SCHEDULE_BUTTONS, schedules.size());

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.auto.schedules.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.page", page + 1, totalPages(schedules.size(), MAX_SCHEDULE_BUTTONS))).append("\n\n");

        for (int index = start; index < end; index++) {
            ScheduleEntry entry = schedules.get(index);
            sb.append(index + 1)
                    .append(". ")
                    .append(formatScheduleSummary(entry, resolveScheduleTargetLabel(entry)));
            if (entry.getNextExecutionAt() != null) {
                sb.append(" ")
                        .append(msg("menu.auto.schedule.next-short",
                                SCHEDULE_TIME_FORMAT.format(entry.getNextExecutionAt())));
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private InlineKeyboardMarkup buildAutoSchedulesKeyboard(String chatId) {
        List<ScheduleEntry> schedules = scheduleService.getSchedules();
        List<String> scheduleIds = new ArrayList<>();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        int page = normalizePage(schedulePageByChat, chatId, schedules.size(), MAX_SCHEDULE_BUTTONS);
        int start = page * MAX_SCHEDULE_BUTTONS;
        int end = Math.min(start + MAX_SCHEDULE_BUTTONS, schedules.size());

        for (int index = start; index < end; index++) {
            ScheduleEntry entry = schedules.get(index);
            scheduleIds.add(entry.getId());
            int pageIndex = index - start;
            String label = "DEL " + truncate(resolveScheduleTargetLabel(entry), 18);
            rows.add(row(button(label, "menu:autoMenu:scheduleDelConfirm:" + pageIndex)));
        }

        scheduleIndexCache.put(chatId, List.copyOf(scheduleIds));
        rows.add(buildPaginationRow(page, schedules.size(), MAX_SCHEDULE_BUTTONS,
                "menu:autoMenu:schedulesPrev", "menu:autoMenu:schedulesNext"));
        rows.add(row(button(msg("menu.auto.btn.refresh"), "menu:autoMenu:schedules")));
        rows.add(row(button(msg("menu.btn.back"), "menu:autoMenu:back")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void showScheduleDeleteConfirm(String chatId, Integer messageId, int scheduleIndex) {
        Optional<String> scheduleIdOptional = resolveScheduleIdByIndex(chatId, scheduleIndex);
        if (scheduleIdOptional.isEmpty()) {
            updateToAutoSchedulesMenu(chatId, messageId);
            return;
        }

        String scheduleId = scheduleIdOptional.get();
        String text = HTML_BOLD_OPEN + msg("menu.auto.confirm.title") + HTML_BOLD_CLOSE_NL
                + msg("menu.auto.confirm.schedule.delete", escapeHtml(resolveScheduleLabelById(scheduleId)));
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(List.of(
                row(button(msg("menu.auto.confirm.yes"), "menu:autoMenu:scheduleDel:" + scheduleIndex),
                        button(msg("menu.auto.confirm.no"), "menu:autoMenu:schedules"))))
                .build();
        editMessage(chatId, messageId, text, keyboard);
    }

    private void handleScheduleDelete(String chatId, Integer messageId, int scheduleIndex) {
        Optional<String> scheduleIdOptional = resolveScheduleIdByIndex(chatId, scheduleIndex);
        if (scheduleIdOptional.isEmpty()) {
            updateToAutoSchedulesMenu(chatId, messageId);
            return;
        }

        String scheduleId = scheduleIdOptional.get();
        try {
            scheduleService.deleteSchedule(scheduleId);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.deleted", resolveScheduleLabelById(scheduleId)));
        } catch (Exception e) {
            log.warn("[Menu] Failed to delete schedule {}", scheduleId, e);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.delete-failed"));
        }

        updateToAutoSchedulesMenu(chatId, messageId);
    }

    private List<TaskContext> buildTaskContexts() {
        List<TaskContext> result = new ArrayList<>();
        List<Goal> goals = autoModeService.getGoals();

        for (Goal goal : goals) {
            if (autoModeService.isInboxGoal(goal) && goal.getTasks().isEmpty()) {
                continue;
            }
            List<AutoTask> sortedTasks = goal.getTasks().stream()
                    .sorted(Comparator.comparingInt(AutoTask::getOrder))
                    .toList();
            for (AutoTask task : sortedTasks) {
                result.add(new TaskContext(goal, task));
            }
        }

        return result;
    }

    private Optional<Goal> resolveGoalByIndex(String chatId, int index) {
        List<String> goalIds = goalIndexCache.get(chatId);
        if (goalIds == null || index < 0 || index >= goalIds.size()) {
            return Optional.empty();
        }
        return autoModeService.getGoal(goalIds.get(index));
    }

    private Optional<TaskContext> resolveTaskContextByIndex(String chatId, int index) {
        List<String> taskKeys = taskIndexCache.get(chatId);
        if (taskKeys == null || index < 0 || index >= taskKeys.size()) {
            return Optional.empty();
        }

        String taskKey = taskKeys.get(index);
        int separatorIndex = taskKey.indexOf('|');
        if (separatorIndex <= 0 || separatorIndex >= taskKey.length() - 1) {
            return Optional.empty();
        }

        String goalId = taskKey.substring(0, separatorIndex);
        String taskId = taskKey.substring(separatorIndex + 1);

        Optional<Goal> goalOptional = autoModeService.getGoal(goalId);
        if (goalOptional.isEmpty()) {
            return Optional.empty();
        }

        Goal goal = goalOptional.get();
        Optional<AutoTask> taskOptional = goal.getTasks().stream()
                .filter(task -> taskId.equals(task.getId()))
                .findFirst();
        return taskOptional.map(autoTask -> new TaskContext(goal, autoTask));
    }

    private Optional<String> resolveScheduleIdByIndex(String chatId, int index) {
        List<String> scheduleIds = scheduleIndexCache.get(chatId);
        if (scheduleIds == null || index < 0 || index >= scheduleIds.size()) {
            return Optional.empty();
        }
        return Optional.of(scheduleIds.get(index));
    }

    private String buildTaskKey(String goalId, String taskId) {
        return goalId + "|" + taskId;
    }

    private int normalizePage(Map<String, Integer> pageByChat, String chatId, int totalItems, int pageSize) {
        int totalPages = totalPages(totalItems, pageSize);
        int current = pageByChat.getOrDefault(chatId, 0);
        if (current < 0) {
            current = 0;
        }
        if (current >= totalPages) {
            current = Math.max(0, totalPages - 1);
        }
        pageByChat.put(chatId, current);
        return current;
    }

    private int totalPages(int totalItems, int pageSize) {
        if (totalItems <= 0) {
            return 1;
        }
        return (totalItems + pageSize - 1) / pageSize;
    }

    private void adjustPage(Map<String, Integer> pageByChat, String chatId, int delta) {
        int current = pageByChat.getOrDefault(chatId, 0);
        pageByChat.put(chatId, Math.max(0, current + delta));
    }

    private InlineKeyboardRow buildPaginationRow(
            int page,
            int totalItems,
            int pageSize,
            String prevCallback,
            String nextCallback) {
        int totalPages = totalPages(totalItems, pageSize);
        boolean hasPrev = page > 0;
        boolean hasNext = page < totalPages - 1;

        String prevLabel = hasPrev ? "◀" : "—";
        String nextLabel = hasNext ? "▶" : "—";
        String indicator = (page + 1) + "/" + totalPages;

        return row(
                button(prevLabel, hasPrev ? prevCallback : "menu:autoMenu:noop"),
                button(indicator, "menu:autoMenu:noop"),
                button(nextLabel, hasNext ? nextCallback : "menu:autoMenu:noop"));
    }

    private Integer parseIndexAction(String action, String prefix) {
        if (action == null || !action.startsWith(prefix)) {
            return null;
        }
        try {
            return Integer.parseInt(action.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handlePlanMenuCallback(String chatId, Integer messageId, String action) {
        if (!planService.isFeatureEnabled()) {
            updateToMainMenu(chatId, messageId);
            return;
        }

        if (action == null || action.isBlank() || "refresh".equals(action)) {
            updateToPlanMenu(chatId, messageId);
            return;
        }

        if (ACTION_BACK.equals(action)) {
            planPageByChat.remove(chatId);
            planIndexCache.remove(chatId);
            updateToMainMenu(chatId, messageId);
            return;
        }

        if ("on".equals(action)) {
            executePlanCommand(chatId, List.of("on"));
            updateToPlanMenu(chatId, messageId);
            return;
        }

        if ("off".equals(action)) {
            executePlanCommand(chatId, List.of("off"));
            updateToPlanMenu(chatId, messageId);
            return;
        }

        if ("done".equals(action)) {
            executePlanCommand(chatId, List.of("done"));
            updateToPlanMenu(chatId, messageId);
            return;
        }

        if ("prev".equals(action)) {
            adjustPage(planPageByChat, chatId, -1);
            updateToPlanMenu(chatId, messageId);
            return;
        }

        if ("next".equals(action)) {
            adjustPage(planPageByChat, chatId, 1);
            updateToPlanMenu(chatId, messageId);
            return;
        }

        Integer detailIndex = parseIndexAction(action, "detail:");
        if (detailIndex != null) {
            showPlanDetail(chatId, messageId, detailIndex);
            return;
        }

        Integer approveIndex = parseIndexAction(action, "approve:");
        if (approveIndex != null) {
            resolvePlanByIndex(chatId, approveIndex)
                    .ifPresent(planContext -> executePlanCommand(chatId, List.of("approve", planContext.plan.getId())));
            showPlanDetail(chatId, messageId, approveIndex);
            return;
        }

        Integer cancelIndex = parseIndexAction(action, "cancel:");
        if (cancelIndex != null) {
            resolvePlanByIndex(chatId, cancelIndex)
                    .ifPresent(planContext -> executePlanCommand(chatId, List.of("cancel", planContext.plan.getId())));
            showPlanDetail(chatId, messageId, cancelIndex);
            return;
        }

        Integer resumeIndex = parseIndexAction(action, "resume:");
        if (resumeIndex != null) {
            resolvePlanByIndex(chatId, resumeIndex)
                    .ifPresent(planContext -> executePlanCommand(chatId, List.of("resume", planContext.plan.getId())));
            showPlanDetail(chatId, messageId, resumeIndex);
            return;
        }

        Integer statusIndex = parseIndexAction(action, "status:");
        if (statusIndex != null) {
            resolvePlanByIndex(chatId, statusIndex)
                    .ifPresent(planContext -> executePlanCommand(chatId, List.of("status", planContext.plan.getId())));
            showPlanDetail(chatId, messageId, statusIndex);
            return;
        }

        updateToPlanMenu(chatId, messageId);
    }

    private void updateToPlanMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildPlanMenuText(chatId), buildPlanMenuKeyboard(chatId));
    }

    private String buildPlanMenuText(String chatId) {
        SessionIdentity sessionIdentity = resolveTelegramSessionIdentity(chatId);
        List<Plan> plans = getSessionPlans(sessionIdentity);
        boolean planModeOn = planService.isPlanModeActive(sessionIdentity);

        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.plan.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.plan.status", planModeOn ? ON : OFF));

        if (plans.isEmpty()) {
            sb.append("\n\n").append(msg("menu.plan.empty"));
            return sb.toString();
        }

        int page = normalizePage(planPageByChat, chatId, plans.size(), MAX_PLAN_BUTTONS);
        int start = page * MAX_PLAN_BUTTONS;
        int end = Math.min(start + MAX_PLAN_BUTTONS, plans.size());

        sb.append("\n").append(msg("menu.auto.page", page + 1, totalPages(plans.size(), MAX_PLAN_BUTTONS)))
                .append("\n\n");
        for (int index = start; index < end; index++) {
            Plan plan = plans.get(index);
            String title = plan.getTitle() != null && !plan.getTitle().isBlank()
                    ? escapeHtml(truncate(plan.getTitle(), 26))
                    : msg("menu.plan.untitled");
            sb.append(index + 1)
                    .append(". ")
                    .append(shortId(plan.getId()))
                    .append(" [")
                    .append(plan.getStatus())
                    .append("] ")
                    .append(title)
                    .append("\n");
        }

        return sb.toString().trim();
    }

    private InlineKeyboardMarkup buildPlanMenuKeyboard(String chatId) {
        SessionIdentity sessionIdentity = resolveTelegramSessionIdentity(chatId);
        List<Plan> plans = getSessionPlans(sessionIdentity);
        List<String> planIds = new ArrayList<>();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(row(
                button(msg("menu.plan.btn.on"), "menu:planMenu:on"),
                button(msg("menu.plan.btn.off"), "menu:planMenu:off"),
                button(msg("menu.plan.btn.done"), "menu:planMenu:done")));

        int page = normalizePage(planPageByChat, chatId, plans.size(), MAX_PLAN_BUTTONS);
        int start = page * MAX_PLAN_BUTTONS;
        int end = Math.min(start + MAX_PLAN_BUTTONS, plans.size());

        for (int index = start; index < end; index++) {
            Plan plan = plans.get(index);
            planIds.add(plan.getId());
            int pageIndex = index - start;
            String title = plan.getTitle() != null && !plan.getTitle().isBlank()
                    ? truncate(plan.getTitle(), 20)
                    : msg("menu.plan.untitled");
            rows.add(row(button(shortId(plan.getId()) + " " + title, "menu:planMenu:detail:" + pageIndex)));
        }

        planIndexCache.put(chatId, List.copyOf(planIds));

        rows.add(buildPaginationRow(page, plans.size(), MAX_PLAN_BUTTONS, "menu:planMenu:prev", "menu:planMenu:next"));
        rows.add(row(button(msg("menu.auto.btn.refresh"), "menu:planMenu:refresh")));
        rows.add(row(button(msg("menu.btn.back"), "menu:planMenu:back")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void showPlanDetail(String chatId, Integer messageId, int planIndex) {
        Optional<PlanContext> planContextOptional = resolvePlanByIndex(chatId, planIndex);
        if (planContextOptional.isEmpty()) {
            updateToPlanMenu(chatId, messageId);
            return;
        }

        Plan plan = planContextOptional.get().plan;
        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.plan.detail.title", shortId(plan.getId())))
                .append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.plan.detail.status", plan.getStatus())).append("\n");
        sb.append(msg("menu.plan.detail.steps", plan.getCompletedStepCount(), plan.getSteps().size())).append("\n");
        if (plan.getModelTier() != null && !plan.getModelTier().isBlank()) {
            sb.append(msg("menu.plan.detail.tier", plan.getModelTier())).append("\n");
        }
        if (plan.getTitle() != null && !plan.getTitle().isBlank()) {
            sb.append(msg("menu.plan.detail.name", escapeHtml(plan.getTitle()))).append("\n");
        }

        List<PlanStep> sortedSteps = plan.getSteps().stream()
                .sorted(Comparator.comparingInt(PlanStep::getOrder))
                .limit(5)
                .toList();
        if (!sortedSteps.isEmpty()) {
            sb.append("\n").append(msg("menu.plan.detail.steps.preview"));
            for (PlanStep planStep : sortedSteps) {
                sb.append("\n")
                        .append("- ")
                        .append(planStep.getToolName())
                        .append(" [")
                        .append(planStep.getStatus())
                        .append("]");
            }
        }

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(List.of(
                row(button(msg("menu.plan.btn.approve"), "menu:planMenu:approve:" + planIndex),
                        button(msg("menu.plan.btn.cancel"), "menu:planMenu:cancel:" + planIndex)),
                row(button(msg("menu.plan.btn.resume"), "menu:planMenu:resume:" + planIndex),
                        button(msg("menu.plan.btn.status"), "menu:planMenu:status:" + planIndex)),
                row(button(msg("menu.btn.back"), "menu:planMenu:refresh")))).build();

        editMessage(chatId, messageId, sb.toString(), keyboard);
    }

    private List<Plan> getSessionPlans(SessionIdentity sessionIdentity) {
        if (sessionIdentity == null) {
            return planService.getPlans();
        }
        List<Plan> plans = planService.getPlans(sessionIdentity);
        if (!plans.isEmpty()) {
            return plans;
        }
        return planService.getPlans();
    }

    private Optional<PlanContext> resolvePlanByIndex(String chatId, int index) {
        List<String> planIds = planIndexCache.get(chatId);
        if (planIds == null || index < 0 || index >= planIds.size()) {
            return Optional.empty();
        }

        String planId = planIds.get(index);
        SessionIdentity sessionIdentity = resolveTelegramSessionIdentity(chatId);
        Optional<Plan> sessionPlan = planService.getPlan(planId, sessionIdentity);
        if (sessionPlan.isPresent()) {
            return Optional.of(new PlanContext(sessionPlan.get()));
        }
        return planService.getPlan(planId).map(PlanContext::new);
    }

    private void executePlanCommand(String chatId, List<String> args) {
        CommandPort router = commandRouter.getIfAvailable();
        if (router == null) {
            return;
        }

        String activeConversationKey = telegramSessionService.resolveActiveConversationKey(chatId);
        String sessionId = CHANNEL_TYPE + ":" + activeConversationKey;
        try {
            CommandPort.CommandResult result = router.execute("plan", args, Map.of(
                    "sessionId", sessionId,
                    "chatId", activeConversationKey,
                    "sessionChatId", activeConversationKey,
                    "transportChatId", chatId,
                    "conversationKey", activeConversationKey,
                    "channelType", CHANNEL_TYPE)).join();
            sendSeparateMessage(chatId, result.output());
        } catch (Exception e) {
            log.error("[Menu] Failed to execute /plan {}", args, e);
        }
    }

    private void startGoalScheduleWizard(String chatId, Integer messageId, int goalIndex) {
        Optional<Goal> goalOptional = resolveGoalByIndex(chatId, goalIndex);
        if (goalOptional.isEmpty()) {
            updateToAutoGoalsMenu(chatId, messageId);
            return;
        }

        Goal goal = goalOptional.get();
        ScheduleDraft draft = new ScheduleDraft(
                ScheduleEntry.ScheduleType.GOAL,
                goal.getId(),
                goal.getTitle(),
                ScheduleReturnTarget.GOAL_DETAIL,
                goalIndex);
        scheduleDraftByChat.put(chatId, draft);
        renderScheduleWizard(chatId, messageId, draft);
    }

    private void startTaskScheduleWizard(String chatId, Integer messageId, int taskIndex) {
        Optional<TaskContext> contextOptional = resolveTaskContextByIndex(chatId, taskIndex);
        if (contextOptional.isEmpty()) {
            updateToAutoTasksMenu(chatId, messageId);
            return;
        }

        TaskContext context = contextOptional.get();
        ScheduleDraft draft = new ScheduleDraft(
                ScheduleEntry.ScheduleType.TASK,
                context.task.getId(),
                context.task.getTitle(),
                ScheduleReturnTarget.TASK_DETAIL,
                taskIndex);
        scheduleDraftByChat.put(chatId, draft);
        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardBack(String chatId, Integer messageId) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        switch (draft.step) {
        case CONFIRM:
            draft.step = ScheduleWizardStep.LIMIT;
            break;
        case LIMIT:
            draft.step = ScheduleWizardStep.TIME;
            break;
        case TIME:
            if (draft.frequency == ScheduleFrequency.DAILY || draft.frequency == ScheduleFrequency.WEEKDAYS) {
                draft.step = ScheduleWizardStep.FREQUENCY;
            } else {
                draft.step = ScheduleWizardStep.DAYS;
            }
            break;
        case DAYS:
            draft.step = ScheduleWizardStep.FREQUENCY;
            break;
        case FREQUENCY:
            cancelScheduleWizard(chatId, messageId);
            return;
        default:
            draft.step = ScheduleWizardStep.FREQUENCY;
            break;
        }

        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardFrequency(String chatId, Integer messageId, String value) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        if ("daily".equals(value)) {
            draft.frequency = ScheduleFrequency.DAILY;
            draft.step = ScheduleWizardStep.TIME;
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        if ("weekdays".equals(value)) {
            draft.frequency = ScheduleFrequency.WEEKDAYS;
            draft.step = ScheduleWizardStep.TIME;
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        if ("weekly".equals(value)) {
            draft.frequency = ScheduleFrequency.WEEKLY;
            draft.days = new HashSet<>(Set.of(1));
            draft.step = ScheduleWizardStep.DAYS;
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        if ("custom".equals(value)) {
            draft.frequency = ScheduleFrequency.CUSTOM_DAYS;
            if (draft.days == null || draft.days.isEmpty()) {
                draft.days = new HashSet<>(Set.of(1));
            }
            draft.step = ScheduleWizardStep.DAYS;
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardToggleDay(String chatId, Integer messageId, int day) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }
        if (!WEEKDAY_SET.contains(day)) {
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        if (draft.days.contains(day)) {
            draft.days.remove(day);
        } else {
            draft.days.add(day);
        }

        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardDaysDone(String chatId, Integer messageId) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        if (draft.days == null || draft.days.isEmpty()) {
            sendSeparateMessage(chatId, msg("menu.auto.schedule.days.required"));
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        draft.step = ScheduleWizardStep.TIME;
        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardTime(String chatId, Integer messageId, String hhmm) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        Optional<TimeValue> parsedTime = parseTimeValue(hhmm);
        if (parsedTime.isEmpty()) {
            sendSeparateMessage(chatId, msg("menu.auto.schedule.time.invalid"));
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        TimeValue timeValue = parsedTime.get();
        draft.hour = timeValue.hour;
        draft.minute = timeValue.minute;
        draft.step = ScheduleWizardStep.LIMIT;
        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardLimit(String chatId, Integer messageId, String value) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        Optional<Integer> parsedLimit = parseLimitValue(value);
        if (parsedLimit.isEmpty()) {
            sendSeparateMessage(chatId, msg("menu.auto.schedule.limit.invalid"));
            renderScheduleWizard(chatId, messageId, draft);
            return;
        }

        draft.maxExecutions = parsedLimit.get();
        draft.step = ScheduleWizardStep.CONFIRM;
        renderScheduleWizard(chatId, messageId, draft);
    }

    private void handleScheduleWizardSave(String chatId, Integer messageId) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }

        try {
            String cron = buildCronFromDraft(draft);
            ScheduleEntry entry = scheduleService.createSchedule(draft.type, draft.targetId, cron, draft.maxExecutions);
            sendSeparateMessage(chatId,
                    msg("menu.auto.schedule.created", entry.getId(), entry.getCronExpression(),
                            describeScheduleLimit(entry)));
            scheduleDraftByChat.remove(chatId);
            returnToScheduleTarget(chatId, messageId, draft);
        } catch (Exception e) {
            log.warn("[Menu] Failed to save schedule from wizard", e);
            sendSeparateMessage(chatId, msg("menu.auto.schedule.create-failed"));
            renderScheduleWizard(chatId, messageId, draft);
        }
    }

    private boolean handlePendingScheduleCustomTime(String chatId, String normalized) {
        PendingInputState pendingInputState = pendingInputByChat.get(chatId);
        if (pendingInputState == null || pendingInputState.type != PendingInputType.SCHEDULE_CUSTOM_TIME) {
            return false;
        }

        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            pendingInputByChat.remove(chatId);
            resendPersistentMenuIfEnabled(chatId);
            return true;
        }

        Optional<TimeValue> parsedTime = parseTimeValue(normalized);
        if (parsedTime.isEmpty()) {
            sendSeparateMessage(chatId, msg("menu.auto.schedule.time.invalid"));
            return true;
        }

        pendingInputByChat.remove(chatId);
        TimeValue timeValue = parsedTime.get();
        draft.hour = timeValue.hour;
        draft.minute = timeValue.minute;
        draft.step = ScheduleWizardStep.LIMIT;
        sendSeparateMessage(chatId, msg("menu.auto.schedule.time.updated",
                String.format(Locale.ROOT, "%02d:%02d", draft.hour, draft.minute)));
        return true;
    }

    private boolean handlePendingScheduleCustomLimit(String chatId, String normalized) {
        PendingInputState pendingInputState = pendingInputByChat.get(chatId);
        if (pendingInputState == null || pendingInputState.type != PendingInputType.SCHEDULE_CUSTOM_LIMIT) {
            return false;
        }

        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            pendingInputByChat.remove(chatId);
            resendPersistentMenuIfEnabled(chatId);
            return true;
        }

        Optional<Integer> parsedLimit = parseLimitValue(normalized);
        if (parsedLimit.isEmpty()) {
            sendSeparateMessage(chatId, msg("menu.auto.schedule.limit.invalid"));
            return true;
        }

        pendingInputByChat.remove(chatId);
        draft.maxExecutions = parsedLimit.get();
        draft.step = ScheduleWizardStep.CONFIRM;
        sendSeparateMessage(chatId, msg("menu.auto.schedule.limit.updated",
                draft.maxExecutions > 0 ? String.valueOf(draft.maxExecutions)
                        : msg("menu.auto.schedule.limit.unlimited")));
        return true;
    }

    private Optional<TimeValue> parseTimeValue(String hhmm) {
        if (hhmm == null) {
            return Optional.empty();
        }

        String normalized = hhmm.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        if (normalized.matches("\\d{4}")) {
            int hour = Integer.parseInt(normalized.substring(0, 2));
            int minute = Integer.parseInt(normalized.substring(2, 4));
            return toTimeValue(hour, minute);
        }

        String[] parts = normalized.split(":");
        if (parts.length != 2) {
            return Optional.empty();
        }

        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return toTimeValue(hour, minute);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<TimeValue> toTimeValue(int hour, int minute) {
        boolean validHour = hour >= 0 && hour <= 23;
        boolean validMinute = minute >= 0 && minute <= 59;
        if (!validHour || !validMinute) {
            return Optional.empty();
        }
        return Optional.of(new TimeValue(hour, minute));
    }

    private Optional<Integer> parseLimitValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                return Optional.empty();
            }
            if (parsed == 0) {
                return Optional.of(-1);
            }
            return Optional.of(parsed);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void startScheduleCustomTimeInput(String chatId) {
        pendingInputByChat.put(chatId, new PendingInputState(PendingInputType.SCHEDULE_CUSTOM_TIME));
    }

    private void startScheduleCustomLimitInput(String chatId) {
        pendingInputByChat.put(chatId, new PendingInputState(PendingInputType.SCHEDULE_CUSTOM_LIMIT));
    }

    private void renderScheduleWizardByChat(String chatId, Integer messageId) {
        ScheduleDraft draft = scheduleDraftByChat.get(chatId);
        if (draft == null) {
            return;
        }
        renderScheduleWizard(chatId, messageId, draft);
    }

    private void cancelScheduleWizard(String chatId, Integer messageId) {
        ScheduleDraft draft = scheduleDraftByChat.remove(chatId);
        if (draft == null) {
            updateToAutoMenu(chatId, messageId);
            return;
        }
        returnToScheduleTarget(chatId, messageId, draft);
    }

    private void renderScheduleWizard(String chatId, Integer messageId, ScheduleDraft draft) {
        editMessage(chatId, messageId, buildScheduleWizardText(draft), buildScheduleWizardKeyboard(draft));
    }

    private String buildScheduleWizardText(ScheduleDraft draft) {
        StringBuilder sb = new StringBuilder();
        sb.append(HTML_BOLD_OPEN).append(msg("menu.auto.schedule.wizard.title")).append(HTML_BOLD_CLOSE_NL);
        sb.append(msg("menu.auto.schedule.wizard.target", localizedScheduleType(draft.type),
                escapeHtml(draft.targetLabel)))
                .append("\n");
        sb.append(msg("menu.auto.schedule.wizard.step", draft.step)).append("\n");
        sb.append(msg("menu.auto.schedule.wizard.frequency", draft.frequency)).append("\n");
        sb.append(msg("menu.auto.schedule.wizard.time",
                String.format(Locale.ROOT, "%02d:%02d", draft.hour, draft.minute)))
                .append("\n");
        sb.append(msg("menu.auto.schedule.wizard.days", formatDaySet(draft.days))).append("\n");

        if (draft.maxExecutions > 0) {
            sb.append(msg("menu.auto.schedule.wizard.limit", draft.maxExecutions)).append("\n");
        } else {
            sb.append(msg("menu.auto.schedule.wizard.limit.unlimited")).append("\n");
        }

        try {
            sb.append("\n").append(msg("menu.auto.schedule.wizard.cron", buildCronFromDraft(draft)));
        } catch (Exception e) {
            sb.append("\n").append(msg("menu.auto.schedule.wizard.cron.pending"));
        }

        return sb.toString();
    }

    private InlineKeyboardMarkup buildScheduleWizardKeyboard(ScheduleDraft draft) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        if (draft.step == ScheduleWizardStep.FREQUENCY) {
            rows.add(row(
                    button(msg("menu.auto.schedule.wizard.freq.daily"), "menu:autoMenu:schFreq:daily"),
                    button(msg("menu.auto.schedule.wizard.freq.weekdays"), "menu:autoMenu:schFreq:weekdays")));
            rows.add(row(
                    button(msg("menu.auto.schedule.wizard.freq.weekly"), "menu:autoMenu:schFreq:weekly"),
                    button(msg("menu.auto.schedule.wizard.freq.custom"), "menu:autoMenu:schFreq:custom")));
        }

        if (draft.step == ScheduleWizardStep.DAYS) {
            rows.add(row(button(dayToggleLabel(draft, 1), "menu:autoMenu:schDay:1"),
                    button(dayToggleLabel(draft, 2), "menu:autoMenu:schDay:2"),
                    button(dayToggleLabel(draft, 3), "menu:autoMenu:schDay:3")));
            rows.add(row(button(dayToggleLabel(draft, 4), "menu:autoMenu:schDay:4"),
                    button(dayToggleLabel(draft, 5), "menu:autoMenu:schDay:5"),
                    button(dayToggleLabel(draft, 6), "menu:autoMenu:schDay:6")));
            rows.add(row(button(dayToggleLabel(draft, 7), "menu:autoMenu:schDay:7")));
            rows.add(row(button(msg("menu.auto.schedule.wizard.days.done"), "menu:autoMenu:schDaysDone")));
        }

        if (draft.step == ScheduleWizardStep.TIME) {
            rows.add(row(button("09:00", "menu:autoMenu:schTime:0900"),
                    button("12:00", "menu:autoMenu:schTime:1200")));
            rows.add(row(button("18:00", "menu:autoMenu:schTime:1800"),
                    button("21:00", "menu:autoMenu:schTime:2100")));
            rows.add(row(button(msg("menu.auto.schedule.wizard.time.custom"), "menu:autoMenu:schTimeCustom")));
        }

        if (draft.step == ScheduleWizardStep.LIMIT) {
            rows.add(row(button("1", "menu:autoMenu:schLimit:1"),
                    button("3", "menu:autoMenu:schLimit:3"),
                    button("5", "menu:autoMenu:schLimit:5")));
            rows.add(row(button("10", "menu:autoMenu:schLimit:10"),
                    button(msg("menu.auto.schedule.wizard.limit.infinite"), "menu:autoMenu:schLimit:0")));
            rows.add(row(button(msg("menu.auto.schedule.wizard.limit.custom"), "menu:autoMenu:schLimitCustom")));
        }

        if (draft.step == ScheduleWizardStep.CONFIRM) {
            rows.add(row(button(msg("menu.auto.schedule.wizard.save"), "menu:autoMenu:schSave")));
        }

        rows.add(row(
                button(msg("menu.btn.back"), "menu:autoMenu:schBack"),
                button(msg("menu.auto.confirm.no"), "menu:autoMenu:schCancel")));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String formatDaySet(Set<Integer> days) {
        if (days == null || days.isEmpty()) {
            return "-";
        }
        List<Integer> sortedDays = days.stream().sorted().toList();
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < sortedDays.size(); index++) {
            if (index > 0) {
                sb.append(", ");
            }
            sb.append(dayShortName(sortedDays.get(index)));
        }
        return sb.toString();
    }

    private void returnToScheduleTarget(String chatId, Integer messageId, ScheduleDraft draft) {
        if (draft.returnTarget == ScheduleReturnTarget.GOAL_DETAIL) {
            showGoalDetail(chatId, messageId, draft.returnIndex);
            return;
        }
        if (draft.returnTarget == ScheduleReturnTarget.TASK_DETAIL) {
            showTaskDetail(chatId, messageId, draft.returnIndex);
            return;
        }
        updateToAutoSchedulesMenu(chatId, messageId);
    }

    private String buildCronFromDraft(ScheduleDraft draft) {
        if (draft.frequency == ScheduleFrequency.DAILY) {
            return String.format(Locale.ROOT, "0 %d %d * * *", draft.minute, draft.hour);
        }
        if (draft.frequency == ScheduleFrequency.WEEKDAYS) {
            return String.format(Locale.ROOT, "0 %d %d * * MON-FRI", draft.minute, draft.hour);
        }

        if (draft.days == null || draft.days.isEmpty()) {
            throw new IllegalArgumentException("No days selected");
        }

        List<Integer> days = draft.days.stream().sorted().toList();
        String dayExpr = days.stream()
                .map(this::dayToCronName)
                .reduce((first, second) -> first + "," + second)
                .orElse("MON");
        String cron = String.format(Locale.ROOT, "0 %d %d * * %s", draft.minute, draft.hour, dayExpr);
        CronExpression.parse(cron);
        return cron;
    }

    private String dayToCronName(int day) {
        return switch (day) {
        case 1 -> "MON";
        case 2 -> "TUE";
        case 3 -> "WED";
        case 4 -> "THU";
        case 5 -> "FRI";
        case 6 -> "SAT";
        case 7 -> "SUN";
        default -> "MON";
        };
    }

    private String dayShortName(int day) {
        return switch (day) {
        case 1 -> "Mon";
        case 2 -> "Tue";
        case 3 -> "Wed";
        case 4 -> "Thu";
        case 5 -> "Fri";
        case 6 -> "Sat";
        case 7 -> "Sun";
        default -> "?";
        };
    }

    private String dayToggleLabel(ScheduleDraft draft, int day) {
        String checked = draft.days.contains(day) ? "[x] " : "[ ] ";
        return checked + dayShortName(day);
    }

    private List<Goal> getDisplayGoals() {
        List<Goal> goals = autoModeService.getGoals();
        return goals.stream()
                .filter(goal -> !autoModeService.isInboxGoal(goal))
                .toList();
    }

    private String renderTaskGroupTitle(Goal goal) {
        if (autoModeService.isInboxGoal(goal)) {
            return msg("menu.auto.inbox.title");
        }
        return goal.getTitle();
    }

    private String resolveScheduleTargetLabel(ScheduleEntry entry) {
        if (entry == null) {
            return "-";
        }
        if (entry.getType() == ScheduleEntry.ScheduleType.GOAL) {
            return autoModeService.getGoal(entry.getTargetId())
                    .map(Goal::getTitle)
                    .orElse(shortId(entry.getTargetId()));
        }
        Optional<Goal> goalOptional = autoModeService.findGoalForTask(entry.getTargetId());
        if (goalOptional.isEmpty()) {
            return shortId(entry.getTargetId());
        }
        Goal goal = goalOptional.get();
        return goal.getTasks().stream()
                .filter(task -> entry.getTargetId().equals(task.getId()))
                .map(AutoTask::getTitle)
                .findFirst()
                .orElse(shortId(entry.getTargetId()));
    }

    private String resolveScheduleLabelById(String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank()) {
            return "-";
        }
        return scheduleService.findSchedule(scheduleId)
                .map(scheduleEntry -> resolveScheduleTargetLabel(scheduleEntry) + " (" + shortId(scheduleId) + ")")
                .orElse(shortId(scheduleId));
    }

    private String describeScheduleLimit(ScheduleEntry entry) {
        if (entry == null || entry.getMaxExecutions() <= 0) {
            return msg("menu.auto.schedule.limit.unlimited");
        }
        return msg("menu.auto.schedule.limit.value", entry.getExecutionCount(), entry.getMaxExecutions());
    }

    private String formatScheduleSummary(ScheduleEntry entry, String targetLabel) {
        String status = entry.isEnabled() ? "ON" : "OFF";
        String limit = describeScheduleLimit(entry);
        return msg("menu.auto.schedule.summary",
                status,
                localizedScheduleType(entry.getType()),
                escapeHtml(truncate(targetLabel, 16)),
                entry.getCronExpression(),
                limit);
    }

    private Optional<AutoTask.TaskStatus> parseTaskStatus(String rawStatus) {
        if (rawStatus == null) {
            return Optional.empty();
        }

        return switch (rawStatus) {
        case "pending" -> Optional.of(AutoTask.TaskStatus.PENDING);
        case "ip" -> Optional.of(AutoTask.TaskStatus.IN_PROGRESS);
        case "done" -> Optional.of(AutoTask.TaskStatus.COMPLETED);
        case "fail" -> Optional.of(AutoTask.TaskStatus.FAILED);
        case "skip" -> Optional.of(AutoTask.TaskStatus.SKIPPED);
        default -> Optional.empty();
        };
    }

    private String goalStatusIcon(Goal goal) {
        return switch (goal.getStatus()) {
        case ACTIVE -> "\u25B6\uFE0F";
        case COMPLETED -> "\u2705";
        case PAUSED -> "\u23F8\uFE0F";
        case CANCELLED -> "\u274C";
        };
    }

    private String taskStatusIcon(AutoTask task) {
        return switch (task.getStatus()) {
        case PENDING -> "[ ]";
        case IN_PROGRESS -> "[>]";
        case COMPLETED -> "[x]";
        case FAILED -> "[!]";
        case SKIPPED -> "[-]";
        };
    }

    private String shortId(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= 8) {
            return value;
        }
        return value.substring(0, 8);
    }

    private void showTaskUpdateFailure(String chatId, Integer messageId) {
        sendSeparateMessage(chatId, msg("menu.auto.task.update-failed"));
        updateToAutoTasksMenu(chatId, messageId);
    }

    private String localizedGoalStatus(Goal.GoalStatus status) {
        return switch (status) {
        case ACTIVE -> msg("menu.auto.status.active");
        case COMPLETED -> msg("menu.auto.status.completed");
        case PAUSED -> msg("menu.auto.status.paused");
        case CANCELLED -> msg("menu.auto.status.cancelled");
        };
    }

    private String localizedTaskStatus(AutoTask.TaskStatus status) {
        return switch (status) {
        case PENDING -> msg("menu.auto.task.status.pending");
        case IN_PROGRESS -> msg("menu.auto.task.status.in-progress");
        case COMPLETED -> msg("menu.auto.task.status.completed");
        case FAILED -> msg("menu.auto.task.status.failed");
        case SKIPPED -> msg("menu.auto.task.status.skipped");
        };
    }

    private String localizedScheduleType(ScheduleEntry.ScheduleType type) {
        return switch (type) {
        case GOAL -> msg("menu.auto.type.goal");
        case TASK -> msg("menu.auto.type.task");
        };
    }

    private void clearAutoMenuCaches(String chatId) {
        goalIndexCache.remove(chatId);
        taskIndexCache.remove(chatId);
        scheduleIndexCache.remove(chatId);
        goalPageByChat.remove(chatId);
        taskPageByChat.remove(chatId);
        schedulePageByChat.remove(chatId);
        planIndexCache.remove(chatId);
        planPageByChat.remove(chatId);
        scheduleDraftByChat.remove(chatId);
        pendingInputByChat.remove(chatId);
    }

    // ==================== Callback handlers ====================

    private void updateToMainMenu(String chatId, Integer messageId) {
        editMessage(chatId, messageId, buildMainMenuText(chatId), buildMainMenuKeyboard(chatId));
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
            resendPersistentMenuIfEnabled(chatId);
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
        SessionIdentity sessionIdentity = resolveTelegramSessionIdentity(chatId);
        if (planService.isPlanModeActive(sessionIdentity)) {
            planService.deactivatePlanMode(sessionIdentity);
        } else {
            planService.activatePlanMode(sessionIdentity, chatId, null);
        }
        updateToMainMenu(chatId, messageId);
    }

    private void handleClose(String chatId, Integer messageId) {
        persistentMenuByChat.put(chatId, false);
        persistentMenuMessageIdByChat.remove(chatId);
        clearAutoMenuCaches(chatId);
        sessionIndexCache.remove(chatId);
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
        String normalizedCallbackData = callbackData;
        if (normalizedCallbackData != null && normalizedCallbackData.length() > MAX_TELEGRAM_CALLBACK_DATA_LENGTH) {
            normalizedCallbackData = normalizedCallbackData.substring(0, MAX_TELEGRAM_CALLBACK_DATA_LENGTH);
        }
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(normalizedCallbackData)
                .build();
    }

    private InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    private boolean editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            return false;
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
            return true;
        } catch (Exception e) {
            log.error("[Menu] Failed to edit message", e);
            return false;
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

    private SessionIdentity resolveTelegramSessionIdentity(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        String activeConversationKey = telegramSessionService.resolveActiveConversationKey(chatId);
        return SessionIdentitySupport.resolveSessionIdentity(CHANNEL_TYPE, activeConversationKey);
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
