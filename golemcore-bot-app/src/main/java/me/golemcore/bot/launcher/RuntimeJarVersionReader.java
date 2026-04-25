package me.golemcore.bot.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import me.golemcore.bot.runtime.RuntimeVersionSupport;

final class RuntimeJarVersionReader {

    private final RuntimeVersionSupport runtimeVersionSupport;

    RuntimeJarVersionReader(RuntimeVersionSupport runtimeVersionSupport) {
        this.runtimeVersionSupport = runtimeVersionSupport;
    }

    String readVersion(Path runtimeJar) {
        if (runtimeJar == null) {
            return null;
        }
        Path fileName = runtimeJar.getFileName();
        if (fileName == null) {
            return null;
        }

        String assetVersion = runtimeVersionSupport.extractVersionFromAssetName(fileName.toString());
        if (assetVersion != null) {
            return assetVersion;
        }

        return readBuildInfoVersion(runtimeJar);
    }

    private String readBuildInfoVersion(Path runtimeJar) {
        try (JarFile jarFile = new JarFile(runtimeJar.toFile())) {
            JarEntry buildInfoEntry = jarFile.getJarEntry(RuntimeLauncher.BUILD_INFO_RESOURCE);
            if (buildInfoEntry == null) {
                return null;
            }
            try (InputStream inputStream = jarFile.getInputStream(buildInfoEntry)) {
                Properties properties = new Properties();
                properties.load(inputStream);
                return LauncherText.trimToNull(properties.getProperty("build.version"));
            }
        } catch (IOException _) {
            return null;
        }
    }
}
