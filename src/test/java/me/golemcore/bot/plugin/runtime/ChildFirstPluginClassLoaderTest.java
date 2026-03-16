package me.golemcore.bot.plugin.runtime;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChildFirstPluginClassLoaderTest {

    @Test
    void shouldDelegateOkHttpClassesToParentLoader(@TempDir Path tempDir) throws Exception {
        Path pluginJar = tempDir.resolve("plugin.jar");
        writeClassCopy(pluginJar, OkHttpClient.class);

        try (ChildFirstPluginClassLoader classLoader = new ChildFirstPluginClassLoader(
                new URL[] { pluginJar.toUri().toURL() },
                getClass().getClassLoader())) {
            Class<?> loaded = classLoader.loadClass("okhttp3.OkHttpClient");

            assertSame(OkHttpClient.class, loaded);
        }
    }

    private void writeClassCopy(Path jarPath, Class<?> type) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            String entryName = type.getName().replace('.', '/') + ".class";
            try (InputStream input = type.getClassLoader().getResourceAsStream(entryName)) {
                assertNotNull(input, "Class bytes must be available for " + type.getName());
                output.putNextEntry(new JarEntry(entryName));
                input.transferTo(output);
                output.closeEntry();
            }
        }
    }
}
