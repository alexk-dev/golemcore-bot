package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogEntryDto {
    private long seq;
    private String timestamp;
    private String level;
    private String logger;
    private String thread;
    private String message;
    private String exception;
}
