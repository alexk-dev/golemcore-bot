package me.golemcore.bot.launcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ForwardedJavaOptionsResolver {

    /**
     * System properties that must follow the child runtime even when the new
     * process is launched from a different entry point.
     */
    private static final List<String> FORWARDED_SYSTEM_PROPERTIES = List.of(
            RuntimeLauncher.STORAGE_PATH_PROPERTY,
            RuntimeLauncher.UPDATE_PATH_PROPERTY,
            RuntimeLauncher.SERVER_PORT_PROPERTY,
            RuntimeLauncher.SERVER_ADDRESS_PROPERTY);

    private final PropertyReader propertyReader;

    ForwardedJavaOptionsResolver(PropertyReader propertyReader) {
        this.propertyReader = propertyReader;
    }

    /**
     * Collects JVM options that must be preserved across launcher restarts.
     *
     * <p>
     * Explicit JVM options from picocli's {@code -J/--java-option} and pass-through
     * {@code -D...} arguments are forwarded verbatim. For a small set of critical
     * properties the launcher also injects values from the parsed launcher options
     * or the current JVM when they were not supplied explicitly.
     *
     * @param launcherArguments
     *            normalized launcher arguments
     * @return JVM options for the child process
     */
    List<String> resolve(LauncherArguments launcherArguments) {
        List<String> forwardedOptions = new ArrayList<>(launcherArguments.explicitJavaOptions());
        Set<String> explicitSystemPropertyNames = extractSystemPropertyNames(forwardedOptions);

        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                RuntimeLauncher.STORAGE_PATH_PROPERTY, launcherArguments.storagePath());
        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                RuntimeLauncher.UPDATE_PATH_PROPERTY, launcherArguments.updatesPath());
        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                RuntimeLauncher.SERVER_PORT_PROPERTY, launcherArguments.serverPort());
        addExplicitSystemProperty(forwardedOptions, explicitSystemPropertyNames,
                RuntimeLauncher.SERVER_ADDRESS_PROPERTY, launcherArguments.serverAddress());

        for (String propertyName : FORWARDED_SYSTEM_PROPERTIES) {
            if (!explicitSystemPropertyNames.contains(propertyName)) {
                addForwardedSystemProperty(forwardedOptions, propertyName);
            }
        }
        return List.copyOf(forwardedOptions);
    }

    private Set<String> extractSystemPropertyNames(List<String> explicitJavaOptions) {
        Set<String> explicitSystemPropertyNames = new LinkedHashSet<>();
        for (String explicitJavaOption : explicitJavaOptions) {
            if (SystemPropertyOption.isSystemPropertyArg(explicitJavaOption)) {
                explicitSystemPropertyNames.add(SystemPropertyOption.extractSystemPropertyName(explicitJavaOption));
            }
        }
        return explicitSystemPropertyNames;
    }

    private void addExplicitSystemProperty(
            List<String> forwardedOptions,
            Set<String> explicitSystemPropertyNames,
            String propertyName,
            String propertyValue) {
        String normalizedValue = LauncherText.trimToNull(propertyValue);
        if (normalizedValue == null || explicitSystemPropertyNames.contains(propertyName)) {
            return;
        }
        forwardedOptions.add(SystemPropertyOption.build(propertyName, normalizedValue));
        explicitSystemPropertyNames.add(propertyName);
    }

    private void addForwardedSystemProperty(List<String> forwardedOptions, String propertyName) {
        String propertyValue = propertyReader.get(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            forwardedOptions.add(SystemPropertyOption.build(propertyName, propertyValue));
        }
    }
}
