package me.golemcore.bot.domain.view;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessagesPageView {
    private String sessionId;
    private List<SessionDetailView.MessageView> messages;
    private boolean hasMore;
    private String oldestMessageId;
}
