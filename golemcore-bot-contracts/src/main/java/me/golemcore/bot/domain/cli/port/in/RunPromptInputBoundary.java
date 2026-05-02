package me.golemcore.bot.domain.cli.port.in;

import me.golemcore.bot.domain.cli.RunRequest;
import me.golemcore.bot.domain.cli.RunResult;

public interface RunPromptInputBoundary {

    RunResult run(RunRequest request);
}
