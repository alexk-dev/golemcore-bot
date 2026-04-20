package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {
    private String channelType;
    private String clientInstanceId;
    private String conversationKey;
    @Builder.Default
    private Boolean activate = true;
}
