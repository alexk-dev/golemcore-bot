package me.golemcore.bot.cli.application.port.in;

import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.domain.cli.ProjectIdentity;
import me.golemcore.bot.domain.cli.ProjectTrust;

public interface ProjectInputBoundary {

    ProjectIdentity discover(CliCommandOptions options);

    ProjectTrust trustStatus(ProjectIdentity project);
}
