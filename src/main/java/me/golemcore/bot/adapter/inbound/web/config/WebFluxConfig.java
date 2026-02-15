package me.golemcore.bot.adapter.inbound.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux configuration for serving the React SPA and static resources. Routes
 * /dashboard/** (except assets) to index.html for client-side routing.
 */
@Configuration
public class WebFluxConfig {

    @Bean
    public RouterFunction<ServerResponse> dashboardStaticResources() {
        return RouterFunctions.resources("/dashboard/assets/**",
                new ClassPathResource("static/dashboard/assets/"));
    }

    @Bean
    public RouterFunction<ServerResponse> dashboardSpaFallback() {
        return RouterFunctions.route()
                .GET("/dashboard", request -> ServerResponse.ok()
                        .bodyValue(new ClassPathResource("static/dashboard/index.html")))
                .GET("/dashboard/", request -> ServerResponse.ok()
                        .bodyValue(new ClassPathResource("static/dashboard/index.html")))
                .GET("/dashboard/{*path}", request -> {
                    String path = request.pathVariable("path");
                    // Serve static files directly, fallback to index.html for SPA routes
                    if (path.startsWith("assets/")) {
                        return ServerResponse.ok()
                                .bodyValue(new ClassPathResource("static/dashboard/" + path));
                    }
                    return ServerResponse.ok()
                            .bodyValue(new ClassPathResource("static/dashboard/index.html"));
                })
                .build();
    }
}
