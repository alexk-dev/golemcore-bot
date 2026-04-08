package me.golemcore.bot.domain.view;

import java.util.List;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetailView {
    private String id;
    private String channelType;
    private String chatId;
    private String conversationKey;
    private String transportChatId;
    private String state;
    private Instant createdAt;
    private Instant updatedAt;
    private List<MessageView> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageView {
        private String id;
        private String role;
        private String content;
        private Instant timestamp;
        private boolean hasToolCalls;
        private boolean hasVoice;
        private String model;
        private String modelTier;
        private String skill;
        private String reasoning;
        private String clientMessageId;
        private boolean autoMode;
        private String autoRunId;
        private String autoScheduleId;
        private String autoGoalId;
        private String autoTaskId;
        private List<AttachmentView> attachments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentView {
        private String type;
        private String name;
        private String mimeType;
        private String directUrl;
        private String internalFilePath;
        private String thumbnailBase64;
    }
}
