package me.golemcore.bot.cli.application.port.out;

import java.util.Map;
import me.golemcore.bot.domain.cli.ProjectIdentity;

public interface ProjectConfigPort {

    Map<String, String> load(ProjectIdentity project);
}
