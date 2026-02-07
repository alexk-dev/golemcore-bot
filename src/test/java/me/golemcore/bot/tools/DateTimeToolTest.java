package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeToolTest {

    private DateTimeTool dateTimeTool;

    @BeforeEach
    void setUp() {
        dateTimeTool = new DateTimeTool();
    }

    @Test
    void getDefinition_returnsCorrectDefinition() {
        ToolDefinition definition = dateTimeTool.getDefinition();

        assertEquals("datetime", definition.getName());
        assertNotNull(definition.getDescription());
    }

    @Test
    void execute_returnsCurrentDateTime() throws ExecutionException, InterruptedException {
        Map<String, Object> params = Map.of();

        ToolResult result = dateTimeTool.execute(params).get();

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertNotNull(result.getData());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertTrue(data.containsKey("year"));
        assertTrue(data.containsKey("month"));
        assertTrue(data.containsKey("dayOfWeek"));
    }

    @Test
    void execute_withTimezone() throws ExecutionException, InterruptedException {
        Map<String, Object> params = Map.of("timezone", "UTC");

        ToolResult result = dateTimeTool.execute(params).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("UTC"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("UTC", data.get("timezone"));
    }

    @Test
    void execute_withInvalidTimezone() throws ExecutionException, InterruptedException {
        Map<String, Object> params = Map.of("timezone", "Invalid/Timezone");

        ToolResult result = dateTimeTool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid timezone"));
    }

    @Test
    void execute_withDifferentTimezones() throws ExecutionException, InterruptedException {
        String[] timezones = { "America/New_York", "Europe/London", "Asia/Tokyo" };

        for (String tz : timezones) {
            Map<String, Object> params = Map.of("timezone", tz);
            ToolResult result = dateTimeTool.execute(params).get();

            assertTrue(result.isSuccess(), "Should work for timezone: " + tz);
        }
    }
}
