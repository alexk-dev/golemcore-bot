package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {

    @Test
    void success_createsSuccessfulResult() {
        ToolResult result = ToolResult.success("Operation completed");

        assertTrue(result.isSuccess());
        assertEquals("Operation completed", result.getOutput());
        assertNull(result.getError());
    }

    @Test
    void success_withData() {
        Map<String, Object> data = Map.of("key", "value");
        ToolResult result = ToolResult.success("Done", data);

        assertTrue(result.isSuccess());
        assertEquals("Done", result.getOutput());
        assertEquals(data, result.getData());
    }

    @Test
    void failure_createsFailedResult() {
        ToolResult result = ToolResult.failure("Something went wrong");

        assertFalse(result.isSuccess());
        assertEquals("Something went wrong", result.getError());
        assertNull(result.getOutput());
    }
}
