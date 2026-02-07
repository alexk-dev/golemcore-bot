/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for getting current date and time.
 *
 * <p>
 * Returns current date/time in a specified timezone (or system default). Output
 * includes formatted string and structured data (year, month, day, hour,
 * minute, etc.).
 *
 * <p>
 * Timezone parameter examples: {@code "America/New_York"},
 * {@code "Europe/London"}, {@code "UTC"}
 *
 * <p>
 * Always enabled.
 */
@Component
public class DateTimeTool implements ToolComponent {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("datetime")
                .description("Get the current date and time. Optionally specify a timezone.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timezone", Map.of(
                                        "type", "string",
                                        "description",
                                        "Timezone (e.g., 'America/New_York', 'Europe/London', 'UTC'). Default is system timezone.")),
                        "required", java.util.List.of()))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String timezoneStr = (String) parameters.get("timezone");
                ZoneId zoneId;

                if (timezoneStr != null && !timezoneStr.isBlank()) {
                    try {
                        zoneId = ZoneId.of(timezoneStr);
                    } catch (Exception e) {
                        return ToolResult.failure("Invalid timezone: " + timezoneStr);
                    }
                } else {
                    zoneId = ZoneId.systemDefault();
                }

                ZonedDateTime now = ZonedDateTime.now(zoneId);
                String formatted = now.format(FORMATTER);

                Map<String, Object> data = Map.of(
                        "datetime", formatted,
                        "timezone", zoneId.getId(),
                        "timestamp", now.toInstant().toEpochMilli(),
                        "dayOfWeek", now.getDayOfWeek().name(),
                        "year", now.getYear(),
                        "month", now.getMonth().name(),
                        "day", now.getDayOfMonth(),
                        "hour", now.getHour(),
                        "minute", now.getMinute());

                return ToolResult.success(formatted, data);

            } catch (Exception e) {
                return ToolResult.failure("Failed to get datetime: " + e.getMessage());
            }
        });
    }
}
