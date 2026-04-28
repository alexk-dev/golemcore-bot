package me.golemcore.bot.domain.update;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpdateMaintenanceWindowTest {

    private final UpdateMaintenanceWindow window = new UpdateMaintenanceWindow();

    @Test
    void shouldStayOpenWhenWindowIsDisabled() {
        UpdateMaintenanceWindow.Status status = window.evaluate(
                false,
                "01:00",
                "03:00",
                Instant.parse("2026-03-20T12:00:00Z"));

        assertTrue(status.open());
        assertNull(status.nextEligibleAt());
    }

    @Test
    void shouldEvaluateSameDayWindowAndNextEligibleTime() {
        UpdateMaintenanceWindow.Status closed = window.evaluate(
                true,
                "01:00",
                "03:00",
                Instant.parse("2026-03-20T00:30:00Z"));
        UpdateMaintenanceWindow.Status open = window.evaluate(
                true,
                "01:00",
                "03:00",
                Instant.parse("2026-03-20T02:15:00Z"));

        assertFalse(closed.open());
        assertEquals(Instant.parse("2026-03-20T01:00:00Z"), closed.nextEligibleAt());
        assertTrue(open.open());
        assertNull(open.nextEligibleAt());
    }

    @Test
    void shouldEvaluateOvernightWindow() {
        UpdateMaintenanceWindow.Status open = window.evaluate(
                true,
                "23:00",
                "02:00",
                Instant.parse("2026-03-20T00:30:00Z"));
        UpdateMaintenanceWindow.Status closed = window.evaluate(
                true,
                "23:00",
                "02:00",
                Instant.parse("2026-03-20T12:00:00Z"));

        assertTrue(open.open());
        assertNull(open.nextEligibleAt());
        assertFalse(closed.open());
        assertEquals(Instant.parse("2026-03-20T23:00:00Z"), closed.nextEligibleAt());
    }

    @Test
    void shouldTreatEqualWindowBoundsAsAlwaysOpen() {
        UpdateMaintenanceWindow.Status status = window.evaluate(
                true,
                "01:00",
                "01:00",
                Instant.parse("2026-03-20T12:00:00Z"));

        assertTrue(status.open());
        assertNull(status.nextEligibleAt());
    }
}
