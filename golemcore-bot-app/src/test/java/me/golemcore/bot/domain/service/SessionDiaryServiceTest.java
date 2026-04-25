package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionDiaryServiceTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private SessionDiaryService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(storagePort.appendText(eq("auto"), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(eq("auto"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        service = new SessionDiaryService(storagePort, objectMapper);
    }

    @Test
    void writeDiaryShouldAttachSessionAndTimestampBeforeAppendingJsonLine() throws Exception {
        DiaryEntry entry = DiaryEntry.builder()
                .type(DiaryEntry.DiaryType.DECISION)
                .content("Created task")
                .build();

        service.writeDiary("session-1", entry);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> lineCaptor = ArgumentCaptor.forClass(String.class);
        verify(storagePort).appendText(eq("auto"), pathCaptor.capture(), lineCaptor.capture());
        assertTrue(pathCaptor.getValue().startsWith("diary/"));
        assertTrue(lineCaptor.getValue().endsWith("\n"));
        DiaryEntry stored = objectMapper.readValue(lineCaptor.getValue(), DiaryEntry.class);
        assertEquals("session-1", stored.getSessionId());
        assertEquals("Created task", stored.getContent());
        assertNotNull(stored.getTimestamp());
        assertEquals("session-1", entry.getSessionId());
        assertNotNull(entry.getTimestamp());
    }

    @Test
    void getRecentDiaryShouldReturnOnlyRequestedSessionInChronologicalOrder() throws Exception {
        String todayPath = "diary/" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
                + ".jsonl";
        String content = objectMapper.writeValueAsString(entry("session-1", "old")) + "\n"
                + objectMapper.writeValueAsString(entry("other-session", "skip")) + "\n"
                + objectMapper.writeValueAsString(entry("session-1", "new")) + "\n";
        when(storagePort.getText(eq("auto"), eq(todayPath))).thenReturn(CompletableFuture.completedFuture(content));

        List<DiaryEntry> entries = service.getRecentDiary("session-1", 10);

        assertEquals(2, entries.size());
        assertEquals("old", entries.get(0).getContent());
        assertEquals("new", entries.get(1).getContent());
        assertEquals("new", service.getRecentDiary("session-1", 0).getFirst().getContent());
    }

    @Test
    void diaryReadAndWriteShouldFallbackOnStorageErrors() {
        when(storagePort.appendText(eq("auto"), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("append failed")));
        when(storagePort.getText(eq("auto"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("read failed")));

        service.writeDiary("session-1", DiaryEntry.builder().content("ignored").build());

        assertTrue(service.getRecentDiary("session-1", 5).isEmpty());
    }

    private static DiaryEntry entry(String sessionId, String content) {
        return DiaryEntry.builder()
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .type(DiaryEntry.DiaryType.PROGRESS)
                .sessionId(sessionId)
                .content(content)
                .build();
    }
}
