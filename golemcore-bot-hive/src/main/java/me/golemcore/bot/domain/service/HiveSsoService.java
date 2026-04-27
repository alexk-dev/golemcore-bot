package me.golemcore.bot.domain.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HiveSsoTokenResponse;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;

public class HiveSsoService {

    private static final String CALLBACK_PATH = "/api/auth/hive/callback";

    private final RuntimeConfigQueryPort runtimeConfigQueryPort;
    private final HiveSessionStateStore hiveSessionStateStore;
    private final HiveGatewayPort hiveGatewayPort;

    public HiveSsoService(
            RuntimeConfigQueryPort runtimeConfigQueryPort,
            HiveSessionStateStore hiveSessionStateStore,
            HiveGatewayPort hiveGatewayPort) {
        this.runtimeConfigQueryPort = runtimeConfigQueryPort;
        this.hiveSessionStateStore = hiveSessionStateStore;
        this.hiveGatewayPort = hiveGatewayPort;
    }

    public HiveSsoStatus getStatus() {
        RuntimeConfig.HiveConfig hiveConfig = HiveRuntimeConfigSupport
                .getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig());
        if (!Boolean.TRUE.equals(hiveConfig.getSsoEnabled())) {
            return HiveSsoStatus.unavailable(false, "Hive SSO is disabled in bot settings");
        }
        Optional<HiveSessionState> sessionState = hiveSessionStateStore.load();
        if (sessionState.isEmpty()) {
            return HiveSsoStatus.unavailable(true, "Hive session is not connected");
        }
        String dashboardBaseUrl = HiveDashboardUrlSupport.normalizeDashboardBaseUrl(hiveConfig.getDashboardBaseUrl());
        if (dashboardBaseUrl == null) {
            return HiveSsoStatus.unavailable(true, "Bot dashboard public URL is not configured");
        }
        return new HiveSsoStatus(true, true, buildAuthorizeUrl(sessionState.get(), dashboardBaseUrl), null);
    }

    public HiveSsoTokenResponse exchange(String code, String codeVerifier) {
        RuntimeConfig.HiveConfig hiveConfig = HiveRuntimeConfigSupport
                .getHiveConfig(runtimeConfigQueryPort.getRuntimeConfig());
        if (!Boolean.TRUE.equals(hiveConfig.getSsoEnabled())) {
            throw new IllegalStateException("Hive SSO is disabled in bot settings");
        }
        HiveSessionState sessionState = hiveSessionStateStore.load()
                .orElseThrow(() -> new IllegalStateException("Hive session is not connected"));
        String dashboardBaseUrl = HiveDashboardUrlSupport.normalizeDashboardBaseUrl(hiveConfig.getDashboardBaseUrl());
        if (dashboardBaseUrl == null) {
            throw new IllegalStateException("Bot dashboard public URL is not configured");
        }
        return hiveGatewayPort.exchangeSsoCode(
                sessionState.getServerUrl(),
                code,
                sessionState.getGolemId(),
                buildRedirectUri(dashboardBaseUrl),
                codeVerifier);
    }

    private String buildAuthorizeUrl(HiveSessionState sessionState, String dashboardBaseUrl) {
        String redirectUri = buildRedirectUri(dashboardBaseUrl);
        String serverUrl = HiveJoinCodeParser.normalizeServerUrl(sessionState.getServerUrl());
        return serverUrl + "/api/v1/oauth2/authorize?response_type=code&client_id="
                + urlEncode(sessionState.getGolemId())
                + "&redirect_uri=" + urlEncode(redirectUri);
    }

    private String buildRedirectUri(String dashboardBaseUrl) {
        return dashboardBaseUrl + CALLBACK_PATH;
    }

    String buildAuthorizeUrlForTest(HiveSessionState sessionState, String dashboardBaseUrl) {
        return buildAuthorizeUrl(sessionState, dashboardBaseUrl);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record HiveSsoStatus(boolean enabled, boolean available, String loginUrl, String reason) {

        static HiveSsoStatus unavailable(boolean enabled, String reason) {
            return new HiveSsoStatus(enabled, false, null, reason);
        }
    }
}
