package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessagesPageDto {
    private String sessionId;
    private List<SessionDetailDto.MessageDto> messages;
    private boolean hasMore;
    private String oldestMessageId;
}
