package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.client.dto.ChangePasswordRequest;
import me.golemcore.bot.client.dto.HiveSsoExchangeRequest;
import me.golemcore.bot.client.dto.HiveSsoStatusResponse;
import me.golemcore.bot.client.dto.LoginRequest;
import me.golemcore.bot.client.dto.LoginResponse;
import me.golemcore.bot.client.dto.MfaDisableRequest;
import me.golemcore.bot.client.dto.MfaEnableRequest;
import me.golemcore.bot.client.dto.MfaSetupResponse;
import me.golemcore.bot.client.dto.MfaStatusResponse;
import me.golemcore.bot.domain.model.AdminCredentials;
import me.golemcore.bot.domain.model.hive.HiveSsoTokenResponse;
import me.golemcore.bot.domain.service.DashboardAuthService;
import me.golemcore.bot.domain.service.HiveSsoService;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Authentication endpoints for the dashboard.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String KEY_SUCCESS = "success";

    private final DashboardAuthService authService;
    private final HiveSsoService hiveSsoService;

    @GetMapping("/mfa-status")
    public Mono<ResponseEntity<MfaStatusResponse>> getMfaStatus() {
        MfaStatusResponse response = MfaStatusResponse.builder()
                .mfaRequired(authService.isMfaEnabled())
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @GetMapping("/hive/sso-status")
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

    @PostMapping("/hive/exchange")
    public Mono<ResponseEntity<LoginResponse>> exchangeHiveSso(@RequestBody HiveSsoExchangeRequest request) {
        HiveSsoTokenResponse tokenResponse = hiveSsoService.exchange(request.getCode(), request.getCodeVerifier());
        DashboardAuthService.TokenPair tokens = authService.authenticateHiveSso(tokenResponse);
        if (tokens == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Hive SSO operator is not allowed");
        }
        LoginResponse response = LoginResponse.builder()
                .accessToken(tokens.getAccessToken())
                .build();
        return Mono.just(ResponseEntity.ok()
                .header("Set-Cookie", buildRefreshCookie(tokens.getRefreshToken()).toString())
                .body(response));
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody LoginRequest request) {
        DashboardAuthService.TokenPair tokens = authService.authenticate(
                request.getPassword(), request.getMfaCode());
        if (tokens == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        LoginResponse response = LoginResponse.builder()
                .accessToken(tokens.getAccessToken())
                .build();
        return Mono.just(ResponseEntity.ok()
                .header("Set-Cookie", buildRefreshCookie(tokens.getRefreshToken()).toString())
                .body(response));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<LoginResponse>> refresh(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(REFRESH_COOKIE);
        if (cookie == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }
        DashboardAuthService.TokenPair tokens = authService.refreshAccessToken(cookie.getValue());
        if (tokens == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }
        LoginResponse response = LoginResponse.builder()
                .accessToken(tokens.getAccessToken())
                .build();
        return Mono.just(ResponseEntity.ok()
                .header("Set-Cookie", buildRefreshCookie(tokens.getRefreshToken()).toString())
                .body(response));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout() {
        ResponseCookie clearCookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .path("/api/auth")
                .maxAge(0)
                .build();
        return Mono.just(ResponseEntity.ok()
                .header("Set-Cookie", clearCookie.toString())
                .build());
    }

    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me() {
        AdminCredentials creds = authService.getCredentials();
        if (creds == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        Map<String, Object> user = Map.of(
                "username", creds.getUsername(),
                "mfaEnabled", creds.isMfaEnabled());
        return Mono.just(ResponseEntity.ok(user));
    }

    @PostMapping("/mfa/setup")
    public Mono<ResponseEntity<MfaSetupResponse>> mfaSetup() {
        DashboardAuthService.MfaSetupResult result = authService.setupMfa();
        MfaSetupResponse response = MfaSetupResponse.builder()
                .secret(result.getSecret())
                .qrUri(result.getQrUri())
                .build();
        return Mono.just(ResponseEntity.ok(response));
    }

    @PostMapping("/mfa/enable")
    public Mono<ResponseEntity<Map<String, Boolean>>> mfaEnable(@RequestBody MfaEnableRequest request) {
        boolean success = authService.enableMfa(request.getSecret(), request.getVerificationCode());
        if (!success) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(KEY_SUCCESS, false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of(KEY_SUCCESS, true)));
    }

    @PostMapping("/mfa/disable")
    public Mono<ResponseEntity<Map<String, Boolean>>> mfaDisable(@RequestBody MfaDisableRequest request) {
        boolean success = authService.disableMfa(request.getPassword());
        if (!success) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(KEY_SUCCESS, false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of(KEY_SUCCESS, true)));
    }

    @PostMapping("/password")
    public Mono<ResponseEntity<Map<String, Boolean>>> changePassword(@RequestBody ChangePasswordRequest request) {
        boolean success = authService.changePassword(request.getOldPassword(), request.getNewPassword());
        if (!success) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(KEY_SUCCESS, false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of(KEY_SUCCESS, true)));
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
