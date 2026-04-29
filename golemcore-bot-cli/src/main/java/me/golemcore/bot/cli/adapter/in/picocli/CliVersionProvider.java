package me.golemcore.bot.cli.adapter.in.picocli;

import picocli.CommandLine.IVersionProvider;

public final class CliVersionProvider implements IVersionProvider {

    private static final String FALLBACK_VERSION = "0.0.0-SNAPSHOT";

    @Override
    public String[] getVersion() {
        String version = CliVersionProvider.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = FALLBACK_VERSION;
        }
        return new String[] {
                "golemcore-bot cli " + version,
                "java " + System.getProperty("java.version", "unknown")
        };
    }
}
