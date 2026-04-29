package me.golemcore.bot.adapter.inbound.web.controller;

import java.time.Duration;
import me.golemcore.bot.client.dto.HiveSsoExchangeRequest;
import me.golemcore.bot.client.dto.HiveSsoStatusResponse;
import me.golemcore.bot.client.dto.LoginResponse;
import me.golemcore.bot.domain.model.hive.HiveSsoTokenResponse;
import me.golemcore.bot.domain.hive.HiveSsoService;
import me.golemcore.bot.port.outbound.DashboardFederatedAuthPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth/hive")
public class HiveSsoAuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final HiveSsoService hiveSsoService;
    private final DashboardFederatedAuthPort dashboardFederatedAuthPort;

    public HiveSsoAuthController(HiveSsoService hiveSsoService,
            DashboardFederatedAuthPort dashboardFederatedAuthPort) {
        this.hiveSsoService = hiveSsoService;
        this.dashboardFederatedAuthPort = dashboardFederatedAuthPort;
    }

    @GetMapping("/sso-status")
    public Mono<ResponseEntity<HiveSsoStatusResponse>> hiveSsoStatus() {
        HiveSsoService.HiveSsoStatus status = hiveSsoService.getStatus();
        HiveSsoStatusResponse response = HiveSsoStatusResponse.builder()
                .enabled(status.enabled())
                .available(status.available())
                .loginUrl(status.loginUrl())
                .reason(status.reason())
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @PostMapping("/exchange")
    public Mono<ResponseEntity<LoginResponse>> exchangeHiveSso(@RequestBody HiveSsoExchangeRequest request) {
        HiveSsoTokenResponse tokenResponse = hiveSsoService.exchange(request.getCode(), request.getCodeVerifier());
        DashboardFederatedAuthPort.DashboardFederatedPrincipal principal = new DashboardFederatedAuthPort.DashboardFederatedPrincipal(
                tokenResponse.username(),
                tokenResponse.displayName(),
                tokenResponse.roles());
        DashboardFederatedAuthPort.DashboardSessionTokens tokens = dashboardFederatedAuthPort
                .issueDashboardTokensForPrincipal(principal);
        if (tokens == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Hive SSO operator is not allowed");
        }
        LoginResponse response = LoginResponse.builder()
                .accessToken(tokens.accessToken())
                .build();
        return Mono.just(ResponseEntity.ok()
                .header("Set-Cookie", buildRefreshCookie(tokens.refreshToken()).toString())
                .body(response));
    }

    private ResponseCookie buildRefreshCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(true)
                .path("/api/auth")
                .maxAge(Duration.ofDays(7))
                .sameSite("Strict")
                .build();
    }
}
