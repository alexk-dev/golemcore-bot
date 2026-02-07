package me.golemcore.bot.adapter.inbound.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramAdapterSplitTest {

    @Test
    void shortTextNotSplit() {
        List<String> result = TelegramAdapter.splitAtNewlines("Hello world", 100);
        assertEquals(1, result.size());
        assertEquals("Hello world", result.get(0));
    }

    @Test
    void splitAtParagraphBreak() {
        String text = "A".repeat(50) + "\n\n" + "B".repeat(50);
        List<String> result = TelegramAdapter.splitAtNewlines(text, 80);
        assertEquals(2, result.size());
        assertEquals("A".repeat(50), result.get(0));
        assertEquals("B".repeat(50), result.get(1));
    }

    @Test
    void splitAtLineBreak() {
        String text = "A".repeat(50) + "\n" + "B".repeat(50);
        List<String> result = TelegramAdapter.splitAtNewlines(text, 80);
        assertEquals(2, result.size());
        assertEquals("A".repeat(50), result.get(0));
        assertEquals("B".repeat(50), result.get(1));
    }

    @Test
    void prefersParagraphOverLine() {
        String text = "A".repeat(30) + "\n\n" + "B".repeat(20) + "\n" + "C".repeat(30);
        List<String> result = TelegramAdapter.splitAtNewlines(text, 60);
        assertEquals(2, result.size());
        assertEquals("A".repeat(30), result.get(0));
        assertEquals("B".repeat(20) + "\n" + "C".repeat(30), result.get(1));
    }

    @Test
    void hardSplitWhenNoNewlines() {
        String text = "A".repeat(200);
        List<String> result = TelegramAdapter.splitAtNewlines(text, 80);
        assertEquals(3, result.size());
        assertEquals("A".repeat(80), result.get(0));
        assertEquals("A".repeat(80), result.get(1));
        assertEquals("A".repeat(40), result.get(2));
    }

    @Test
    void multipleChunks() {
        String text = "Para1\n\nPara2\n\nPara3\n\nPara4";
        List<String> result = TelegramAdapter.splitAtNewlines(text, 12);
        // Each paragraph fits in 12 chars, but combined they don't
        assertTrue(result.size() >= 2, "should split into multiple chunks");
        String joined = String.join("", result);
        // All content preserved (minus the \n\n separators)
        assertTrue(joined.contains("Para1"));
        assertTrue(joined.contains("Para4"));
    }

    @Test
    void preservesMarkdownFormatting() {
        // **bold** should not be split across chunks
        String text = "Line 1\n\n**Bold Title**\nSome content here";
        List<String> result = TelegramAdapter.splitAtNewlines(text, 20);
        // Verify no chunk has orphaned **
        for (String chunk : result) {
            long asteriskPairs = chunk.chars().filter(c -> c == '*').count();
            assertTrue(asteriskPairs % 2 == 0,
                    "each chunk should have even number of * chars: " + chunk);
        }
    }
}
