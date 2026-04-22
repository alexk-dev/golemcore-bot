package me.golemcore.bot.domain.system.toolloop.view;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics for one request-time context projection.
 */
public final class ContextHygieneReport {

    private int rawTokens;
    private int projectedTokens;
    private int systemPromptTokens;
    private int historyTokens;
    private int memoryTokens;
    private int toolTokens;
    private int compressed;
    private int pinned;
    private final Map<GarbageReason, Integer> dropped = new EnumMap<>(GarbageReason.class);

    public void setRawTokens(int rawTokens) {
        this.rawTokens = Math.max(0, rawTokens);
    }

    public void setProjectedTokens(int projectedTokens) {
        this.projectedTokens = Math.max(0, projectedTokens);
    }

    public void setSystemPromptTokens(int systemPromptTokens) {
        this.systemPromptTokens = Math.max(0, systemPromptTokens);
    }

    public void setHistoryTokens(int historyTokens) {
        this.historyTokens = Math.max(0, historyTokens);
    }

    public void setMemoryTokens(int memoryTokens) {
        this.memoryTokens = Math.max(0, memoryTokens);
    }

    public void setToolTokens(int toolTokens) {
        this.toolTokens = Math.max(0, toolTokens);
    }

    public void incrementCompressed() {
        compressed++;
    }

    public void incrementPinned() {
        pinned++;
    }

    public void drop(GarbageReason reason) {
        GarbageReason effectiveReason = reason != null ? reason : GarbageReason.BUDGET_EXCEEDED;
        dropped.merge(effectiveReason, 1, Integer::sum);
    }

    public boolean changed() {
        return compressed > 0 || !dropped.isEmpty() || rawTokens != projectedTokens;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rawTokens", rawTokens);
        values.put("projectedTokens", projectedTokens);
        values.put("systemPromptTokens", systemPromptTokens);
        values.put("historyTokens", historyTokens);
        values.put("memoryTokens", memoryTokens);
        values.put("toolTokens", toolTokens);
        values.put("dropped", droppedAsMap());
        values.put("compressed", compressed);
        values.put("pinned", pinned);
        return values;
    }

    public String summary() {
        return "contextHygiene rawTokens=" + rawTokens
                + " projectedTokens=" + projectedTokens
                + " compressed=" + compressed
                + " dropped=" + droppedAsMap();
    }

    private Map<String, Integer> droppedAsMap() {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Map.Entry<GarbageReason, Integer> entry : dropped.entrySet()) {
            values.put(entry.getKey().name(), entry.getValue());
        }
        return values;
    }
}
