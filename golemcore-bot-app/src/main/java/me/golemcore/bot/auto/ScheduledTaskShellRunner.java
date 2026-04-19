package me.golemcore.bot.auto;

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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.tools.ShellTool;
import org.springframework.stereotype.Component;

/**
 * Runs shell-based scheduled tasks through the existing shell tool sandbox.
 */
@Component
public class ScheduledTaskShellRunner {

    private final ShellTool shellTool;

    public ScheduledTaskShellRunner(ShellTool shellTool) {
        this.shellTool = shellTool;
    }

    public ShellRunResult run(ScheduledTask task, int timeoutMinutes)
            throws InterruptedException, ExecutionException, TimeoutException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("command", task.getShellCommand());
        parameters.put("timeout", Math.max(1, timeoutMinutes) * 60);
        if (!StringValueSupport.isBlank(task.getShellWorkingDirectory())) {
            parameters.put("workdir", task.getShellWorkingDirectory());
        }

        ToolResult toolResult = shellTool.execute(parameters)
                .get(Math.max(1, timeoutMinutes) + 1L, TimeUnit.MINUTES);
        return toShellRunResult(toolResult);
    }

    private ShellRunResult toShellRunResult(ToolResult toolResult) {
        Map<String, Object> data = toolResult.getData() instanceof Map<?, ?> raw
                ? castMap(raw)
                : Map.of();
        Integer exitCode = data.get("exitCode") instanceof Number number ? number.intValue() : null;
        if (toolResult.isSuccess()) {
            String output = !StringValueSupport.isBlank(toolResult.getOutput())
                    ? toolResult.getOutput()
                    : "Command completed successfully";
            return new ShellRunResult(true, output, output, null);
        }

        String summary = !StringValueSupport.isBlank(toolResult.getError())
                ? toolResult.getError()
                : !StringValueSupport.isBlank(toolResult.getOutput())
                        ? toolResult.getOutput()
                        : "Shell command failed";
        String reportBody = !StringValueSupport.isBlank(toolResult.getOutput())
                ? toolResult.getOutput()
                : summary;
        String fingerprint = exitCode != null ? "shell_exit_" + exitCode : "shell_command_failed";
        return new ShellRunResult(false, summary, reportBody, fingerprint);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    public record ShellRunResult(boolean success, String summary, String reportBody, String fingerprint) {
    }
}
