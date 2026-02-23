package me.golemcore.bot.port.inbound;

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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Port for executing user commands such as slash commands (/skills, /status,
 * etc.). Commands bypass the agent loop and provide direct access to system
 * functions.
 */
public interface CommandPort {

    /**
     * Executes a command with the given arguments and context.
     *
     * @param command
     *            Command name (without leading slash)
     * @param args
     *            List of command arguments
     * @param context
     *            Execution context containing chatId, metadata, etc.
     * @return Command execution result with success status and output
     */
    CompletableFuture<CommandResult> execute(String command, List<String> args, Map<String, Object> context);

    /**
     * Checks if a command with the given name is registered.
     */
    boolean hasCommand(String command);

    /**
     * Checks if a command is available for the given channel.
     */
    default boolean hasCommand(String command, String channelType) {
        return hasCommand(command);
    }

    /**
     * Returns a list of all available commands with their definitions.
     */
    List<CommandDefinition> listCommands();

    /**
     * Returns commands available for the given channel.
     */
    default List<CommandDefinition> listCommands(String channelType) {
        return listCommands();
    }

    /**
     * Represents the result of a command execution including success status and output message.
     */
    record CommandResult(
            boolean success,
            String output,
            Object data
    ) {
        /**
         * Creates a successful command result.
         */
        public static CommandResult success(String output) {
            return new CommandResult(true, output, null);
        }

        /**
         * Creates a failed command result with error message.
         */
        public static CommandResult failure(String error) {
            return new CommandResult(false, error, null);
        }
    }

    /**
     * Defines a command's metadata including name, description, and usage examples.
     */
    record CommandDefinition(
            String name,
            String description,
            String usage
    ) {}
}
