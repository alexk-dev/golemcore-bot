package me.golemcore.bot.launcher;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;

final class DefaultBundledRuntimeResolver implements BundledRuntimeResolver {

    @Override
    public Path resolve() {
        try {
            CodeSource codeSource = RuntimeLauncher.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            return Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
