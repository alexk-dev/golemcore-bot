package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSessionResponse {
    private String channelType;
    private String clientInstanceId;
    private String transportChatId;
    private String conversationKey;
    private String sessionId;
    private String source;
}
