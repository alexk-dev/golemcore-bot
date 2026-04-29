package me.golemcore.bot.cli.application.port.in;

import me.golemcore.bot.domain.cli.RunRequest;
import me.golemcore.bot.domain.cli.RunResult;

public interface RunPromptInputBoundary {

    RunResult run(RunRequest request);
}
