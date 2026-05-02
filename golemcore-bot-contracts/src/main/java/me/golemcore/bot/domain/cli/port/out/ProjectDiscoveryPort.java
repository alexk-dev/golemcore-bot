package me.golemcore.bot.domain.cli.port.out;

import java.nio.file.Path;
import me.golemcore.bot.domain.cli.ProjectIdentity;

public interface ProjectDiscoveryPort {

    ProjectIdentity discover(Path cwd);
}
