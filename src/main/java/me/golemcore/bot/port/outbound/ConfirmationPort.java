package me.golemcore.bot.port.outbound;

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

import java.util.concurrent.CompletableFuture;

/**
 * Port for requesting user confirmation before executing potentially
 * destructive tool operations. Used by ToolConfirmationPolicy to enforce safety
 * checks on dangerous commands.
 */
public interface ConfirmationPort {

    /**
     * Request confirmation from the user for a destructive action.
     *
     * @param chatId
     *            the chat to send the confirmation request to
     * @param toolName
     *            the tool requesting confirmation
     * @param description
     *            human-readable description of the action
     * @return future that completes with true (approved) or false (denied/timeout)
     */
    CompletableFuture<Boolean> requestConfirmation(String chatId, String toolName, String description);

    /**
     * Check if the confirmation port is available (e.g. Telegram client is
     * connected).
     */
    boolean isAvailable();
}
