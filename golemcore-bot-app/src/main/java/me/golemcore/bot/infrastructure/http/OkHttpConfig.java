package me.golemcore.bot.infrastructure.http;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.infrastructure.config.BotProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring configuration for OkHttp client with connection pooling and timeouts.
 *
 * <p>
 * Creates a shared {@link OkHttpClient} bean configured from
 * {@link BotProperties}:
 * <ul>
 * <li>Connect timeout - time to establish connection</li>
 * <li>Read timeout - time to wait for data</li>
 * <li>Write timeout - time to send data</li>
 * <li>Connection pool - maintains idle connections for reuse</li>
 * <li>Retry on failure - automatically retries failed connections</li>
 * </ul>
 *
 * <p>
 * This client is used by Feign clients and other HTTP adapters.
 *
 * @since 1.0
 */
@Configuration
@RequiredArgsConstructor
public class OkHttpConfig {

    private final BotProperties properties;

    @Bean
    public OkHttpClient okHttpClient() {
        BotProperties.HttpProperties http = properties.getHttp();

        return new OkHttpClient.Builder()
                .connectTimeout(http.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(http.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(http.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(
                        http.getMaxIdleConnections(),
                        http.getKeepAliveDuration(),
                        TimeUnit.MILLISECONDS))
                .retryOnConnectionFailure(true)
                .build();
    }
}
