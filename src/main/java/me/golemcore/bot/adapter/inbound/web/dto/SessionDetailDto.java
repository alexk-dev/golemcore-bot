package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetailDto {
    private String id;
    private String channelType;
    private String chatId;
    private String conversationKey;
    private String transportChatId;
    private String state;
    private String createdAt;
    private String updatedAt;
    private List<MessageDto> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageDto {
        private String id;
        private String role;
        private String content;
        private String timestamp;
        private boolean hasToolCalls;
        private boolean hasVoice;
        private String model;
        private String modelTier;
        private String reasoning;
        private String clientMessageId;
        private boolean autoMode;
        private String autoRunId;
        private String autoScheduleId;
        private String autoGoalId;
        private String autoTaskId;
        private List<AttachmentDto> attachments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentDto {
        private String type;
        private String name;
        private String mimeType;
        private String url;
    }
}
