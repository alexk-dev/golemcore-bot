package me.golemcore.bot.adapter.outbound.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;

class JwtDashboardTokenAdapterTest {

    @Test
    void shouldDelegateTokenOperationsToJwtProvider() {
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        JwtDashboardTokenAdapter adapter = new JwtDashboardTokenAdapter(jwtTokenProvider);

        when(jwtTokenProvider.generateAccessToken("admin")).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken("admin")).thenReturn("refresh-token");
        when(jwtTokenProvider.validateToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("access-token")).thenReturn("admin");

        assertEquals("access-token", adapter.generateAccessToken("admin"));
        assertEquals("refresh-token", adapter.generateRefreshToken("admin"));
        assertTrue(adapter.validateToken("access-token"));
        assertTrue(adapter.isRefreshToken("refresh-token"));
        assertEquals("admin", adapter.getUsernameFromToken("access-token"));
        assertFalse(adapter.isRefreshToken("access-token"));

        verify(jwtTokenProvider).generateAccessToken("admin");
        verify(jwtTokenProvider).generateRefreshToken("admin");
        verify(jwtTokenProvider).validateToken("access-token");
        verify(jwtTokenProvider).isRefreshToken("refresh-token");
        verify(jwtTokenProvider).isRefreshToken("access-token");
        verify(jwtTokenProvider).getUsernameFromToken("access-token");
    }
}
