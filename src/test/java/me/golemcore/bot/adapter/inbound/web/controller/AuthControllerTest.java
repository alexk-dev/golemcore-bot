package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.AdminCredentials;
import me.golemcore.bot.domain.service.DashboardAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.golemcore.bot.adapter.inbound.web.dto.ChangePasswordRequest;
import me.golemcore.bot.adapter.inbound.web.dto.LoginRequest;
import me.golemcore.bot.adapter.inbound.web.dto.LoginResponse;
import me.golemcore.bot.adapter.inbound.web.dto.MfaDisableRequest;
import me.golemcore.bot.adapter.inbound.web.dto.MfaEnableRequest;
import me.golemcore.bot.adapter.inbound.web.dto.MfaSetupResponse;
import me.golemcore.bot.adapter.inbound.web.dto.MfaStatusResponse;

import java.util.Map;

class AuthControllerTest {

    private DashboardAuthService authService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(DashboardAuthService.class);
        controller = new AuthController(authService);
    }

    @Test
    void shouldReturnMfaStatus() {
        when(authService.isMfaEnabled()).thenReturn(false);

        StepVerifier.create(controller.getMfaStatus())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    MfaStatusResponse body = response.getBody();
                    assertNotNull(body);
                    assertFalse(body.isMfaRequired());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnMfaStatusEnabled() {
        when(authService.isMfaEnabled()).thenReturn(true);

        StepVerifier.create(controller.getMfaStatus())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertTrue(response.getBody().isMfaRequired());
                })
                .verifyComplete();
    }

    @Test
    void shouldLoginSuccessfully() {
        DashboardAuthService.TokenPair tokens = DashboardAuthService.TokenPair.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .build();
        when(authService.authenticate("password", null)).thenReturn(tokens);

        LoginRequest request = LoginRequest.builder().password("password").build();

        StepVerifier.create(controller.login(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    LoginResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("access-token", body.getAccessToken());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn401OnFailedLogin() {
        when(authService.authenticate("wrong", null)).thenReturn(null);

        LoginRequest request = LoginRequest.builder().password("wrong").build();

        StepVerifier.create(controller.login(request))
                .assertNext(response -> assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldLogout() {
        StepVerifier.create(controller.logout())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getHeaders().get("Set-Cookie"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnMeWhenAuthenticated() {
        AdminCredentials creds = AdminCredentials.builder()
                .username("admin")
                .mfaEnabled(false)
                .build();
        when(authService.getCredentials()).thenReturn(creds);

        StepVerifier.create(controller.me())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    Map<String, Object> body = response.getBody();
                    assertNotNull(body);
                    assertEquals("admin", body.get("username"));
                    assertEquals(false, body.get("mfaEnabled"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn401MeWhenNoCredentials() {
        when(authService.getCredentials()).thenReturn(null);

        StepVerifier.create(controller.me())
                .assertNext(response -> assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldSetupMfa() {
        DashboardAuthService.MfaSetupResult result = DashboardAuthService.MfaSetupResult.builder()
                .secret("SECRET")
                .qrUri("otpauth://totp/GolemCore:admin?secret=SECRET")
                .build();
        when(authService.setupMfa()).thenReturn(result);

        StepVerifier.create(controller.mfaSetup())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    MfaSetupResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("SECRET", body.getSecret());
                })
                .verifyComplete();
    }

    @Test
    void shouldEnableMfa() {
        when(authService.enableMfa("SECRET", "123456")).thenReturn(true);

        MfaEnableRequest request = MfaEnableRequest.builder()
                .secret("SECRET")
                .verificationCode("123456")
                .build();

        StepVerifier.create(controller.mfaEnable(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(true, response.getBody().get("success"));
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectInvalidMfaEnable() {
        when(authService.enableMfa("SECRET", "000000")).thenReturn(false);

        MfaEnableRequest request = MfaEnableRequest.builder()
                .secret("SECRET")
                .verificationCode("000000")
                .build();

        StepVerifier.create(controller.mfaEnable(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
                    assertEquals(false, response.getBody().get("success"));
                })
                .verifyComplete();
    }

    @Test
    void shouldDisableMfa() {
        when(authService.disableMfa("password")).thenReturn(true);

        MfaDisableRequest request = MfaDisableRequest.builder().password("password").build();

        StepVerifier.create(controller.mfaDisable(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(true, response.getBody().get("success"));
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectMfaDisableWithWrongPassword() {
        when(authService.disableMfa("wrong")).thenReturn(false);

        MfaDisableRequest request = MfaDisableRequest.builder().password("wrong").build();

        StepVerifier.create(controller.mfaDisable(request))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldChangePassword() {
        when(authService.changePassword("old", "new")).thenReturn(true);

        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .oldPassword("old")
                .newPassword("new")
                .build();

        StepVerifier.create(controller.changePassword(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(true, response.getBody().get("success"));
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectPasswordChangeWithWrongOld() {
        when(authService.changePassword("wrong", "new")).thenReturn(false);

        ChangePasswordRequest request = ChangePasswordRequest.builder()
                .oldPassword("wrong")
                .newPassword("new")
                .build();

        StepVerifier.create(controller.changePassword(request))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }
}
