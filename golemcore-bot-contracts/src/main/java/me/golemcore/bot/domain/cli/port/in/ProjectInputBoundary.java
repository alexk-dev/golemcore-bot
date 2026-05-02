package me.golemcore.bot.domain.cli.port.in;

import java.nio.file.Path;
import me.golemcore.bot.domain.cli.ProjectIdentity;
import me.golemcore.bot.domain.cli.ProjectTrust;

public interface ProjectInputBoundary {

    ProjectIdentity discover(Path cwd);

    ProjectTrust trustStatus(ProjectIdentity project);
}
