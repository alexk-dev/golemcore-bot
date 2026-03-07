package me.golemcore.bot.plugin.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Child-first loader for plugin-private dependencies while delegating engine
 * and framework packages to the parent loader.
 */
public class ChildFirstPluginClassLoader extends URLClassLoader {

    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.",
            "javax.",
            "jakarta.",
            "org.slf4j.",
            "org.springframework.",
            "org.apache.commons.logging.",
            "ch.qos.logback.",
            "com.fasterxml.jackson.",
            "me.golemcore.plugin.api.");

    public ChildFirstPluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loadedClass = findLoadedClass(name);
            if (loadedClass == null) {
                if (isParentFirst(name)) {
                    try {
                        loadedClass = getParent().loadClass(name);
                    } catch (ClassNotFoundException ex) {
                        loadedClass = findClass(name);
                    }
                } else {
                    try {
                        loadedClass = findClass(name);
                    } catch (ClassNotFoundException ex) {
                        loadedClass = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(loadedClass);
            }
            return loadedClass;
        }
    }

    private boolean isParentFirst(String className) {
        return PARENT_FIRST_PREFIXES.stream().anyMatch(className::startsWith);
    }
}
