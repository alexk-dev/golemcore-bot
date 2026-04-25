package me.golemcore.bot.launcher;

import picocli.CommandLine.IVersionProvider;

final class RuntimeLauncherVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String implementationVersion = RuntimeLauncher.class.getPackage().getImplementationVersion();
        if (implementationVersion == null || implementationVersion.isBlank()) {
            implementationVersion = "development";
        }
        return new String[] { "golemcore-bot native launcher " + implementationVersion };
    }
}
