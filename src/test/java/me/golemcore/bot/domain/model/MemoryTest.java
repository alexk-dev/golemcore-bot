package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryTest {

    @Test
    void toContext_buildsCorrectContext() {
        Memory memory = Memory.builder()
                .longTermContent("I am a helpful assistant")
                .todayNotes("User asked about weather")
                .recentDays(List.of(
                        Memory.DailyNote.builder()
                                .date(LocalDate.of(2024, 1, 1))
                                .content("Previous conversation")
                                .build()))
                .build();

        String context = memory.toContext();

        assertTrue(context.contains("## Long-term Memory"));
        assertTrue(context.contains("I am a helpful assistant"));
        assertTrue(context.contains("## Today's Notes"));
        assertTrue(context.contains("User asked about weather"));
        assertTrue(context.contains("## Recent Context"));
        assertTrue(context.contains("2024-01-01"));
    }

    @Test
    void toContext_handlesEmptyMemory() {
        Memory memory = Memory.builder().build();
        String context = memory.toContext();
        assertEquals("", context);
    }

    @Test
    void toContext_handlesPartialMemory() {
        Memory memory = Memory.builder()
                .longTermContent("Only long term")
                .build();

        String context = memory.toContext();

        assertTrue(context.contains("Only long term"));
        assertFalse(context.contains("Today's Notes"));
    }
}
