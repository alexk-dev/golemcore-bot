package me.golemcore.bot.domain;

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

/**
 * Global constants for the bot application.
 *
 * <p>
 * Single-user design — no per-user partitioning in storage or session
 * management. All state (sessions, goals, preferences) uses a single default
 * user identifier.
 *
 * @since 1.0
 */
public final class BotConstants {
    public static final String DEFAULT_USER = "default";

    private BotConstants() {
    }
}
