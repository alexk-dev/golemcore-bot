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
public class LogsPageResponse {
    private List<LogEntryDto> items;
    private Long oldestSeq;
    private Long newestSeq;
    private boolean hasMore;
}
