package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateStatus {
    private UpdateState state;
    private boolean enabled;
    private UpdateVersionInfo current;
    private UpdateVersionInfo staged;
    private UpdateVersionInfo available;
    private Instant lastCheckAt;
    private String lastError;
}
