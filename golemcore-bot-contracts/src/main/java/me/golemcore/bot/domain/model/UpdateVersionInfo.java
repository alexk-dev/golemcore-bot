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
public class UpdateVersionInfo {
    private String version;
    private String source;
    private String tag;
    private String assetName;
    private Instant preparedAt;
    private Instant publishedAt;
}
