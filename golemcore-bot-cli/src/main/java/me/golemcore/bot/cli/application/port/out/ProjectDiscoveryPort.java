package me.golemcore.bot.cli.application.port.out;

import java.nio.file.Path;
import me.golemcore.bot.domain.cli.ProjectIdentity;

public interface ProjectDiscoveryPort {

    ProjectIdentity discover(Path cwd);
}
