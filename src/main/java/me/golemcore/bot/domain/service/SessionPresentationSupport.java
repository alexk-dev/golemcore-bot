package me.golemcore.bot.domain.service;

import java.util.List;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.view.SessionSummaryView;

public final class SessionPresentationSupport {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LEN = 64;
    private static final int PREVIEW_MAX_LEN = 160;
    private static final int START_WITH_INDEX = 0;
    private static final String DEFAULT_SESSION_TITLE = "New session";

    private SessionPresentationSupport() {
    }

    public static SessionSummaryView toSummary(AgentSession session, boolean active) {
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
        return SessionSummaryView.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .conversationKey(conversationKey)
                .transportChatId(transportChatId)
                .messageCount(getVisibleMessages(session).size())
                .state(session.getState() != null ? session.getState().name() : "ACTIVE")
                .createdAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null)
                .updatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null)
                .title(buildTitle(session, conversationKey))
                .preview(buildPreview(session))
                .active(active)
                .build();
    }

    public static List<Message> getVisibleMessages(AgentSession session) {
        if (session == null || session.getMessages() == null) {
            return List.of();
        }
        return session.getMessages().stream()
                .filter(message -> message != null
                        && ("user".equals(message.getRole()) || ROLE_ASSISTANT.equals(message.getRole()))
                        && isHistoryVisibleMessage(message))
                .toList();
    }

    public static boolean isHistoryVisibleMessage(Message message) {
        if (message == null || message.isInternalMessage()) {
            return false;
        }
        return hasVisibleContent(message.getContent()) || resolveAttachmentCount(message) > START_WITH_INDEX;
    }

    public static String resolveMessageContent(Message message) {
        if (message == null) {
            return null;
        }
        if (hasVisibleContent(message.getContent())) {
            return message.getContent();
        }

        int attachmentCount = resolveAttachmentCount(message);
        if (attachmentCount <= START_WITH_INDEX) {
            return message.getContent();
        }

        return attachmentCount == 1
                ? "[1 attachment]"
                : "[" + attachmentCount + " attachments]";
    }

    public static int resolveAttachmentCount(Message message) {
        if (message == null || message.getMetadata() == null) {
            return START_WITH_INDEX;
        }
        Object attachmentsValue = message.getMetadata().get("attachments");
        if (!(attachmentsValue instanceof List<?> attachments)) {
            return START_WITH_INDEX;
        }
        return attachments.size();
    }

    public static String buildTitle(AgentSession session, String conversationKey) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return DEFAULT_SESSION_TITLE;
        }

        for (Message message : session.getMessages()) {
            if (message == null || !"user".equals(message.getRole()) || message.isInternalMessage()) {
                continue;
            }
            String content = message.getContent();
            if (!StringValueSupport.isBlank(content)) {
                return truncate(content.trim(), TITLE_MAX_LEN);
            }
        }

        if (!StringValueSupport.isBlank(conversationKey)) {
            return "Session " + truncate(conversationKey, 12);
        }
        return DEFAULT_SESSION_TITLE;
    }

    public static String buildPreview(AgentSession session) {
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return null;
        }

        String preview = null;
        for (Message message : session.getMessages()) {
            if (message == null || message.isInternalMessage() || StringValueSupport.isBlank(message.getContent())) {
                continue;
            }
            preview = truncate(message.getContent().trim(), PREVIEW_MAX_LEN);
        }
        return preview;
    }

    private static boolean hasVisibleContent(String content) {
        return content != null && !content.trim().isEmpty();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
