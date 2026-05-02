package me.golemcore.bot.domain.cli.port.out;

import me.golemcore.bot.domain.cli.RunRequest;
import me.golemcore.bot.domain.cli.RunResult;

public interface AgentRuntimePort {

    RunResult run(RunRequest request);
}
