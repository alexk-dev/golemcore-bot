package me.golemcore.bot.domain.hive;

final class HiveDashboardUrlSupport {

    private static final String DASHBOARD_PATH = "/dashboard";

    private HiveDashboardUrlSupport() {
    }

    static String normalizeDashboardBaseUrl(String dashboardBaseUrl) {
        if (dashboardBaseUrl == null || dashboardBaseUrl.isBlank()) {
            return null;
        }
        String normalized = dashboardBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(DASHBOARD_PATH)) {
            return normalized;
        }
        return normalized + DASHBOARD_PATH;
    }
}
