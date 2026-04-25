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

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.okhttp.OkHttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Factory for creating Feign HTTP clients with OkHttp transport and Jackson
 * JSON encoding.
 *
 * <p>
 * Feign provides declarative REST client creation from Java interfaces. This
 * factory configures all clients with:
 * <ul>
 * <li>OkHttp transport - shared connection pool and timeouts</li>
 * <li>Jackson encoder/decoder - JSON serialization using shared
 * ObjectMapper</li>
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * MyApi client = factory.create(MyApi.class, "https://api.example.com");
 * }</pre>
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
public class FeignClientFactory {

    private final okhttp3.OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    /**
     * Create a Feign client for the given API interface.
     */
    public <T> T create(Class<T> apiType, String baseUrl) {
        return Feign.builder()
                .client(new OkHttpClient(okHttpClient))
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(new JacksonDecoder(objectMapper))
                .target(apiType, baseUrl);
    }

    /**
     * Create a Feign client with custom options.
     */
    public <T> T create(Class<T> apiType, String baseUrl, Feign.Builder builder) {
        return builder
                .client(new OkHttpClient(okHttpClient))
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(new JacksonDecoder(objectMapper))
                .target(apiType, baseUrl);
    }
}
