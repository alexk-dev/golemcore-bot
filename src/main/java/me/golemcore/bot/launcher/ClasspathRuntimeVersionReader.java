package me.golemcore.bot.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the version baked into the bundled image runtime.
 *
 * <p>
 * The context class loader is preferred to satisfy PMD and to work in
 * environments where the launcher class loader is not the one that sees the
 * build-info resource. A system-classloader fallback keeps the lookup stable
 * when no context loader is set.
 * </p>
 */
final class ClasspathRuntimeVersionReader implements RuntimeVersionReader {

    @Override
    public String currentVersion() {
        try (InputStream inputStream = readBuildInfo()) {
            if (inputStream == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            String version = properties.getProperty("build.version");
            return version == null || version.isBlank() ? "dev" : version;
        } catch (IOException ignored) {
            return "dev";
        }
    }

    private InputStream readBuildInfo() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            InputStream contextStream = contextClassLoader.getResourceAsStream(RuntimeLauncher.BUILD_INFO_RESOURCE);
            if (contextStream != null) {
                return contextStream;
            }
        }
        return ClassLoader.getSystemResourceAsStream(RuntimeLauncher.BUILD_INFO_RESOURCE);
    }
}
