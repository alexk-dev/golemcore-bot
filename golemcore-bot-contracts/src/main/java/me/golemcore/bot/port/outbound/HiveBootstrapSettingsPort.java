package me.golemcore.bot.port.outbound;

/**
 * Domain-facing access to startup Hive bootstrap overrides.
 */
public interface HiveBootstrapSettingsPort {

    HiveBootstrapSettings hiveBootstrapSettings();

    record HiveBootstrapSettings(
            Boolean enabled,
            Boolean autoConnectOnStartup,
            String joinCode,
            String displayName,
            String hostLabel,
            String dashboardBaseUrl,
            Boolean ssoEnabled) {

        public static HiveBootstrapSettings empty() {
            return new HiveBootstrapSettings(null, null, null, null, null, null, null);
        }
    }
}
