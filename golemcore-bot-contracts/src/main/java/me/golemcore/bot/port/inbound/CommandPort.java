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
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;

/**
 * Port for executing user commands such as slash commands (/skills, /status,
 * etc.). Commands bypass the agent loop and provide direct access to system
 * functions.
 */
public interface CommandPort {

    /**
     * Executes a typed command invocation. New adapters should prefer this method
     * so command routing does not depend on ad-hoc context maps.
     *
     * @param invocation
     *            transport-neutral command invocation
     * @return structured command outcome suitable for surface-specific presenters
     */
    default CompletableFuture<CommandOutcome> execute(CommandInvocation invocation) {
        CommandInvocation safeInvocation = invocation == null
                ? CommandInvocation.of("", List.of(), "", null)
                : invocation;
        return execute(
                safeInvocation.command(),
                safeInvocation.args(),
                safeInvocation.context().toLegacyMap())
                .thenApply(CommandResult::toOutcome);
    }

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
     * Returns a list of all available commands with their definitions.
     */
    List<CommandDefinition> listCommands();

    /**
     * Represents the result of a command execution including success status and output message.
     *
     * @param success
     *            whether the command completed successfully
     * @param output
     *            user-facing command output
     * @param data
     *            optional structured command payload
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

        /**
         * Creates a legacy command result from a structured outcome.
         */
        public static CommandResult fromOutcome(CommandOutcome outcome) {
            if (outcome == null) {
                return failure("");
            }
            return new CommandResult(outcome.success(), outcome.fallbackText(), outcome.data());
        }

        /**
         * Converts this legacy result into a structured outcome.
         */
        public CommandOutcome toOutcome() {
            return CommandOutcome.text(success, output, data);
        }
    }

    /**
     * Defines a command's metadata including name, description, and usage examples.
     *
     * @param name
     *            command name without the leading slash
     * @param description
     *            short command description
     * @param usage
     *            usage example or syntax hint
     */
    record CommandDefinition(
            String name,
            String description,
            String usage
    ) {}
}
