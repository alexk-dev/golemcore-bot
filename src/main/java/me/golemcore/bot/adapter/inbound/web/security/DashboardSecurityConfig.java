package me.golemcore.bot.adapter.inbound.web.security;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the dashboard (WebFlux reactive). When
 * dashboard is disabled, all requests are permitted (no-op).
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class DashboardSecurityConfig {

    private final BotProperties botProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        Customizer<ServerHttpSecurity.CsrfSpec> csrfCustomizer = csrf -> csrf
                .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                .requireCsrfProtectionMatcher(
                        new NegatedServerWebExchangeMatcher(
                                ServerWebExchangeMatchers.pathMatchers("/api/**", "/ws/**")));

        if (!botProperties.getDashboard().isEnabled()) {
            return http
                    .csrf(csrfCustomizer)
                    .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                    .build();
        }

        return http
                .csrf(csrfCustomizer)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .addFilterBefore(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/auth/login", "/api/auth/mfa-status", "/api/auth/refresh").permitAll()
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers("/dashboard/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/", "/favicon.ico").permitAll()
                        .pathMatchers("/api/**").hasRole("ADMIN")
                        .anyExchange().permitAll())
                .build();
    }

    @SuppressWarnings("java:S5122") // CORS is intentionally configurable via bot.dashboard.cors-allowed-origins
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        String origins = botProperties.getDashboard().getCorsAllowedOrigins();
        if (origins != null && !origins.isBlank()) {
            config.setAllowedOrigins(Arrays.asList(origins.split(",")));
        } else {
            config.setAllowedOriginPatterns(List.of("*"));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
