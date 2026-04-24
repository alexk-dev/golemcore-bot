package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class NativeLauncherConfigurationTest {

    private static final String STRICT_CLI_ENTRYPOINT = "me.golemcore.bot.launcher.RuntimeCliLauncher";
    private static final String LEGACY_ENTRYPOINT = "me.golemcore.bot.launcher.RuntimeLauncher";
    private static final Pattern LAUNCHER_MAIN_CLASS = Pattern.compile("(?m)^LAUNCHER_MAIN_CLASS=\"([^\"]+)\"$");

    @Test
    void native_distribution_should_package_strict_cli_entrypoint_explicitly() throws Exception {
        String script = Files.readString(Path.of(".github/scripts/build-native-distribution.sh"));
        Matcher matcher = LAUNCHER_MAIN_CLASS.matcher(script);

        assertTrue(matcher.find(), "Native distribution script must define LAUNCHER_MAIN_CLASS");
        assertEquals(STRICT_CLI_ENTRYPOINT, matcher.group(1));
        assertTrue(script.contains("jar --create"));
        assertEquals(2, countOccurrences(script, "--main-class \"${LAUNCHER_MAIN_CLASS}\""));
        assertEquals(0, countOccurrences(script, "LAUNCHER_MAIN_CLASS=\"" + LEGACY_ENTRYPOINT + "\""));
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while (true) {
            int index = text.indexOf(needle, offset);
            if (index < 0) {
                return count;
            }
            count++;
            offset = index + needle.length();
        }
    }
}
