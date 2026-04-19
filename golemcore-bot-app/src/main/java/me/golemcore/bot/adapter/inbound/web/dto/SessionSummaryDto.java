package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummaryDto {
    private String id;
    private String channelType;
    private String chatId;
    private String conversationKey;
    private String transportChatId;
    private int messageCount;
    private String state;
    private String createdAt;
    private String updatedAt;
    private String title;
    private String preview;
    private boolean active;
}
