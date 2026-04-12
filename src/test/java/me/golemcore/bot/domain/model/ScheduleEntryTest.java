package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScheduleEntryTest {

    @Test
    void shouldSetLegacyReportChannelType() {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setLegacyReportChannelType("telegram");

        assertNotNull(entry.getReport());
        assertEquals("telegram", entry.getReport().getChannelType());
    }

    @Test
    void shouldSetLegacyReportChatId() {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setLegacyReportChatId("  12345  ");

        assertNotNull(entry.getReport());
        assertEquals("12345", entry.getReport().getChatId());
    }

    @Test
    void shouldSetLegacyReportWebhookUrl() {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setLegacyReportWebhookUrl("https://example.com/hook");

        assertNotNull(entry.getReport());
        assertEquals("https://example.com/hook", entry.getReport().getWebhookUrl());
    }

    @Test
    void shouldSetLegacyReportWebhookSecret() {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setLegacyReportWebhookSecret("secret-token");

        assertNotNull(entry.getReport());
        assertEquals("secret-token", entry.getReport().getWebhookBearerToken());
    }

    @Test
    void shouldIgnoreBlankLegacyReportFields() {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setLegacyReportChannelType("");
        entry.setLegacyReportChatId(null);
        entry.setLegacyReportWebhookUrl("   ");

        assertNull(entry.getReport());
    }

    @Test
    void shouldReuseExistingReportForMultipleLegacySetters() {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setLegacyReportChannelType("telegram");
        entry.setLegacyReportChatId("99999");

        assertNotNull(entry.getReport());
        assertEquals("telegram", entry.getReport().getChannelType());
        assertEquals("99999", entry.getReport().getChatId());
    }

    @Test
    void shouldReportExhaustedWhenCountReachesMax() {
        ScheduleEntry entry = ScheduleEntry.builder()
                .maxExecutions(3)
                .executionCount(3)
                .build();

        assertTrue(entry.isExhausted());
    }

    @Test
    void shouldNotReportExhaustedForUnlimitedSchedule() {
        ScheduleEntry entry = ScheduleEntry.builder()
                .maxExecutions(-1)
                .executionCount(100)
                .build();

        assertFalse(entry.isExhausted());
    }

    @Test
    void shouldNotReportExhaustedWhenCountBelowMax() {
        ScheduleEntry entry = ScheduleEntry.builder()
                .maxExecutions(5)
                .executionCount(2)
                .build();

        assertFalse(entry.isExhausted());
    }
}
