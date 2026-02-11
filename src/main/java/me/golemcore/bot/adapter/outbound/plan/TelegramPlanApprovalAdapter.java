package me.golemcore.bot.adapter.outbound.plan;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.model.PlanApprovalCallbackEvent;
import me.golemcore.bot.domain.model.PlanExecutionCompletedEvent;
import me.golemcore.bot.domain.model.PlanReadyEvent;
import me.golemcore.bot.domain.model.PlanStep;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.infrastructure.config.BotProperties;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Telegram adapter for plan approval UI. Sends inline keyboard prompts for plan
 * approval and handles approval/cancellation callbacks. Follows the
 * {@link me.golemcore.bot.adapter.outbound.confirmation.TelegramConfirmationAdapter}
 * pattern for lazy TelegramClient initialization.
 */
@Component
@Slf4j
public class TelegramPlanApprovalAdapter {

    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_CANCEL = "cancel";

    private final PlanService planService;
    private final PlanExecutionService planExecutionService;
    private final BotProperties properties;

    private final AtomicReference<TelegramClient> telegramClient = new AtomicReference<>();

    public TelegramPlanApprovalAdapter(PlanService planService,
            PlanExecutionService planExecutionService,
            BotProperties properties) {
        this.planService = planService;
        this.planExecutionService = planExecutionService;
        this.properties = properties;
    }

    /**
     * Set the TelegramClient instance. Package-private for testing.
     */
    void setTelegramClient(TelegramClient client) {
        this.telegramClient.set(client);
    }

    @EventListener
    public void onPlanReady(PlanReadyEvent event) {
        if (!planService.isFeatureEnabled()) {
            return;
        }

        planService.getPlan(event.planId()).ifPresent(plan -> sendPlanForApproval(event.chatId(), plan));
    }

    @EventListener
    public void onPlanApproval(PlanApprovalCallbackEvent event) {
        if (!planService.isFeatureEnabled()) {
            return;
        }

        String planId = event.planId();
        String action = event.action();

        if (ACTION_APPROVE.equals(action)) {
            try {
                planService.approvePlan(planId);
                updateMessage(event.chatId(), event.messageId(),
                        "\u2705 Plan approved. Executing...");
                planExecutionService.executePlan(planId);
                log.info("[PlanApproval] Plan '{}' approved and execution started", planId);
            } catch (Exception e) {
                log.error("[PlanApproval] Failed to approve plan '{}'", planId, e);
                updateMessage(event.chatId(), event.messageId(),
                        "\u274C Failed to approve plan: " + e.getMessage());
            }
        } else if (ACTION_CANCEL.equals(action)) {
            try {
                planService.cancelPlan(planId);
                updateMessage(event.chatId(), event.messageId(),
                        "\u274C Plan cancelled.");
                log.info("[PlanApproval] Plan '{}' cancelled", planId);
            } catch (Exception e) {
                log.error("[PlanApproval] Failed to cancel plan '{}'", planId, e);
            }
        }
    }

    @EventListener
    public void onPlanExecutionCompleted(PlanExecutionCompletedEvent event) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            log.debug("[PlanApproval] TelegramClient not available, cannot send execution summary");
            return;
        }
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(event.chatId())
                    .text(event.summary())
                    .parseMode("Markdown")
                    .build();
            client.execute(message);
            log.debug("[PlanApproval] Sent execution summary for plan '{}'", event.planId());
        } catch (Exception e) { // NOSONAR
            log.error("[PlanApproval] Failed to send execution summary for plan '{}'", event.planId(), e);
        }
    }

    private void sendPlanForApproval(String chatId, Plan plan) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            log.warn("[PlanApproval] TelegramClient not available, cannot send approval UI");
            return;
        }

        String text = buildApprovalMessage(plan);
        String planId = plan.getId();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("\u2705 Approve")
                                .callbackData("plan:" + planId + ":approve")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("\u274C Cancel")
                                .callbackData("plan:" + planId + ":cancel")
                                .build()))
                .build();

        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build();
            client.execute(message);
            log.debug("[PlanApproval] Sent approval UI for plan '{}'", planId);
        } catch (Exception e) {
            log.error("[PlanApproval] Failed to send approval message", e);
        }
    }

    private String buildApprovalMessage(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCCB <b>Plan Ready for Approval</b>\n\n");

        if (plan.getTitle() != null) {
            sb.append("<b>").append(escapeHtml(plan.getTitle())).append("</b>\n\n");
        }

        sb.append("<b>Steps (").append(plan.getSteps().size()).append("):</b>\n");
        for (int i = 0; i < plan.getSteps().size(); i++) {
            PlanStep step = plan.getSteps().get(i);
            sb.append(i + 1).append(". <code>").append(escapeHtml(step.getToolName())).append("</code>");
            if (step.getDescription() != null && !step.getDescription().isBlank()) {
                sb.append(" — ").append(escapeHtml(truncate(step.getDescription(), 80)));
            }
            sb.append("\n");
        }

        if (plan.getModelTier() != null) {
            sb.append("\nTier: ").append(plan.getModelTier());
        }

        return sb.toString();
    }

    private void updateMessage(String chatId, String messageId, String text) {
        TelegramClient client = getOrCreateClient();
        if (client == null) {
            return;
        }
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(Integer.parseInt(messageId))
                    .text(text)
                    .build();
            client.execute(edit);
        } catch (Exception e) {
            log.error("[PlanApproval] Failed to update message", e);
        }
    }

    private TelegramClient getOrCreateClient() {
        TelegramClient client = this.telegramClient.get();
        if (client != null) {
            return client;
        }
        BotProperties.ChannelProperties channelProps = properties.getChannels().get("telegram");
        if (channelProps == null || !channelProps.isEnabled()) {
            return null;
        }
        String token = channelProps.getToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        TelegramClient newClient = new OkHttpTelegramClient(token);
        this.telegramClient.set(newClient);
        log.debug("[PlanApproval] TelegramClient lazily initialized");
        return newClient;
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
