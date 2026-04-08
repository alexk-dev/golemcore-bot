package me.golemcore.bot.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSessionSelectionView {
    private String channelType;
    private String clientInstanceId;
    private String transportChatId;
    private String conversationKey;
    private String sessionId;
    private String source;
}
