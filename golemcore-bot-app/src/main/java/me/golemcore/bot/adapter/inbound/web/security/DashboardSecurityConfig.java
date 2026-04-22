package me.golemcore.bot.adapter.inbound.web.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.DashboardPublicPathPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

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
    private final List<DashboardPublicPathPort> dashboardPublicPathPorts;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        // CSRF disabled — authentication uses JWT bearer tokens (inherently CSRF-safe)
        if (!botProperties.getDashboard().isEnabled()) {
            return http
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                    .build();
        }

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(exceptionHandlingSpec -> exceptionHandlingSpec
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            exchange.getResponse().getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
                            return exchange.getResponse().setComplete();
                        })
                        .accessDeniedHandler((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            exchange.getResponse().getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
                            return exchange.getResponse().setComplete();
                        }))
                .addFilterBefore(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(getPublicApiPaths()).permitAll()
                        .pathMatchers("/api/hooks/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/telegram/webhook").permitAll()
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers("/dashboard/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/", "/favicon.ico").permitAll()
                        .pathMatchers("/api/models/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").hasRole("ADMIN")
                        .anyExchange().permitAll())
                .build();
    }

    private String[] getPublicApiPaths() {
        Set<String> publicApiPaths = new LinkedHashSet<>();
        publicApiPaths.add("/api/auth/login");
        publicApiPaths.add("/api/auth/mfa-status");
        publicApiPaths.add("/api/auth/refresh");
        for (DashboardPublicPathPort dashboardPublicPathPort : dashboardPublicPathPorts) {
            if (dashboardPublicPathPort == null || dashboardPublicPathPort.getPublicPathPatterns() == null) {
                continue;
            }
            for (String path : dashboardPublicPathPort.getPublicPathPatterns()) {
                if (path != null && !path.isBlank()) {
                    publicApiPaths.add(path.trim());
                }
            }
        }
        return publicApiPaths.toArray(new String[0]);
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
