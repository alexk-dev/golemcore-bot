package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.port.outbound.StoragePort;

final class LegacySessionDiaryService extends SessionDiaryService {

    private static final String AUTO_DIR = "auto";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    LegacySessionDiaryService(StoragePort storagePort, ObjectMapper objectMapper) {
        super(storagePort, objectMapper);
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DiaryEntry> getRecentDiary(String sessionId, int count) {
        try {
            List<DiaryEntry> entries = new ArrayList<>();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            int normalizedCount = Math.max(1, count);
            for (int i = 0; i < 7 && entries.size() < normalizedCount; i++) {
                appendEntriesForDate(entries, sessionId, today.minusDays(i), normalizedCount);
            }
            Collections.reverse(entries);
            return entries;
        } catch (IOException | RuntimeException exception) { // NOSONAR - legacy tests expect best-effort diary reads
            return List.of();
        }
    }

    private void appendEntriesForDate(List<DiaryEntry> entries, String sessionId, LocalDate date, int limit)
            throws IOException {
        String content = storagePort.getText(AUTO_DIR, diaryPath(date)).join();
        if (content == null || content.isBlank()) {
            return;
        }
        String[] lines = content.split("\n");
        for (int index = lines.length - 1; index >= 0 && entries.size() < limit; index--) {
            appendEntry(entries, sessionId, lines[index]);
        }
    }

    private void appendEntry(List<DiaryEntry> entries, String sessionId, String line) throws IOException {
        if (line.isBlank()) {
            return;
        }
        DiaryEntry entry = objectMapper.readValue(line, DiaryEntry.class);
        if (entry.getSessionId() == null || sessionId.equals(entry.getSessionId())) {
            entries.add(entry);
        }
    }

    private String diaryPath(LocalDate date) {
        return "diary/" + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".jsonl";
    }
}
