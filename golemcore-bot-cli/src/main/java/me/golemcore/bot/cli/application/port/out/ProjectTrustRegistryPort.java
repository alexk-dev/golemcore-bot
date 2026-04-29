package me.golemcore.bot.cli.application.port.out;

import me.golemcore.bot.domain.cli.ProjectIdentity;
import me.golemcore.bot.domain.cli.ProjectTrust;

public interface ProjectTrustRegistryPort {

    ProjectTrust trustStatus(ProjectIdentity project);
}
