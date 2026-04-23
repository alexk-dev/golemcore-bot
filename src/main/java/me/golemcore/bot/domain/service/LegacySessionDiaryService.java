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
                String date = today.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String content = storagePort.getText(AUTO_DIR, "diary/" + date + ".jsonl").join();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String[] lines = content.split("\n");
                for (int j = lines.length - 1; j >= 0 && entries.size() < normalizedCount; j--) {
                    if (lines[j].isBlank()) {
                        continue;
                    }
                    DiaryEntry entry = objectMapper.readValue(lines[j], DiaryEntry.class);
                    if (entry.getSessionId() == null || sessionId.equals(entry.getSessionId())) {
                        entries.add(entry);
                    }
                }
            }
            Collections.reverse(entries);
            return entries;
        } catch (IOException | RuntimeException exception) { // NOSONAR - legacy tests expect best-effort diary reads
            return List.of();
        }
    }
}
