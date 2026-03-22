package me.golemcore.bot.domain.model.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceStorageBudget {
    private long maxCompressedBytes;
}
