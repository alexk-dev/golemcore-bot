package me.golemcore.bot.cli.application.port.in;

import java.util.Map;
import me.golemcore.bot.cli.domain.CliCommandOptions;

public interface ConfigInputBoundary {

    Map<String, String> effectiveConfig(CliCommandOptions options);
}
