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

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Tool for web search using Brave Search API.
 *
 * <p>
 * Provides web search functionality via Brave Search API. Returns search
 * results with titles, descriptions, and URLs.
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code bot.tools.brave-search.enabled} - Enable/disable
 * <li>{@code bot.tools.brave-search.api-key} - Brave API key (required)
 * <li>{@code bot.tools.brave-search.default-count} - Number of results (default
 * 5)
 * </ul>
 *
 * <p>
 * Brave Search API: Free tier provides 2000 queries/month.
 *
 * @see <a href="https://brave.com/search/api/">Brave Search API</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BraveSearchTool implements ToolComponent {

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_COUNT = "count";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_OBJECT = "object";

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final FeignClientFactory feignClientFactory;
    private final BotProperties properties;
    private final UserPreferencesService userPreferencesService;

    private BraveSearchApi searchApi;
    private boolean enabled;
    private String apiKey;
    private int defaultCount;

    @PostConstruct
    public void init() {
        var config = properties.getTools().getBraveSearch();
        this.enabled = config.isEnabled();
        this.apiKey = config.getApiKey();
        this.defaultCount = config.getDefaultCount();

        if (enabled && (apiKey == null || apiKey.isBlank())) {
            log.warn("Brave Search tool is enabled but API key is not configured. Disabling.");
            this.enabled = false;
        }

        if (enabled) {
            this.searchApi = feignClientFactory.create(BraveSearchApi.class, "https://api.search.brave.com");
            log.info("Brave Search tool initialized (default results: {})", defaultCount);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("brave_search")
                .description(
                        "Search the web using Brave Search. Returns titles, URLs, and descriptions of search results.")
                .inputSchema(Map.of(
                        "type", TYPE_OBJECT,
                        "properties", Map.of(
                                PARAM_QUERY, Map.of(
                                        "type", TYPE_STRING,
                                        "description", "The search query"),
                                PARAM_COUNT, Map.of(
                                        "type", TYPE_INTEGER,
                                        "description", "Number of results to return (1-20, default: " + 5 + ")")),
                        "required", List.of(PARAM_QUERY)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String query = (String) parameters.get(PARAM_QUERY);
            if (query == null || query.isBlank()) {
                return ToolResult.failure("Search query is required");
            }

            int count = defaultCount;
            if (parameters.containsKey(PARAM_COUNT)) {
                Object countObj = parameters.get(PARAM_COUNT);
                if (countObj instanceof Number n) {
                    count = Math.max(1, Math.min(20, n.intValue()));
                }
            }

            return executeWithRetry(query, count);
        });
    }

    private ToolResult executeWithRetry(String query, int count) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.debug("Brave Search: query='{}', count={}, attempt={}", query, count, attempt);

                BraveSearchResponse response = searchApi.search(apiKey, query, count);
                return buildSuccessResult(query, response);

            } catch (FeignException e) {
                if (e.status() == HTTP_TOO_MANY_REQUESTS && attempt < MAX_RETRIES) {
                    long backoffMs = (long) (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt));
                    log.warn("[BraveSearch] Rate limit hit (attempt {}/{}), retrying in {}ms",
                            attempt + 1, MAX_RETRIES, backoffMs);
                    sleep(backoffMs);
                } else if (e.status() == HTTP_TOO_MANY_REQUESTS) {
                    log.error("[BraveSearch] Rate limit exceeded after {} retries for query: {}", MAX_RETRIES, query);
                    return ToolResult.failure(userPreferencesService.getMessage("tool.brave.rate_limit"));
                } else {
                    log.error("[BraveSearch] API error (status {}) for query: {}", e.status(), query, e);
                    return ToolResult.failure(userPreferencesService.getMessage("tool.brave.error"));
                }
            } catch (Exception e) { // NOSONAR - broad catch for unexpected errors
                log.error("[BraveSearch] Unexpected error for query: {}", query, e);
                return ToolResult.failure(userPreferencesService.getMessage("tool.brave.error"));
            }
        }
        return ToolResult.failure(userPreferencesService.getMessage("tool.brave.error"));
    }

    private ToolResult buildSuccessResult(String query, BraveSearchResponse response) {
        if (response.getWeb() == null || response.getWeb().getResults() == null
                || response.getWeb().getResults().isEmpty()) {
            return ToolResult.success("No results found for: " + query);
        }

        List<WebResult> results = response.getWeb().getResults();

        String output = results.stream()
                .map(r -> String.format("**%s**%n%s%n%s",
                        r.getTitle(),
                        r.getUrl(),
                        r.getDescription() != null ? r.getDescription() : ""))
                .collect(Collectors.joining("\n\n"));

        String header = String.format("Search results for \"%s\" (%d results):%n%n", query, results.size());

        return ToolResult.success(header + output, Map.of(
                PARAM_QUERY, query,
                PARAM_COUNT, results.size(),
                "results", results.stream()
                        .map(r -> Map.of(
                                "title", r.getTitle() != null ? r.getTitle() : "",
                                "url", r.getUrl() != null ? r.getUrl() : "",
                                "description", r.getDescription() != null ? r.getDescription() : ""))
                        .toList()));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BraveSearch retry sleep interrupted", e);
        }
    }

    // Feign API interface
    interface BraveSearchApi {
        @RequestLine("GET /res/v1/web/search?q={query}&count={count}")
        @Headers({
                "Accept: application/json",
                "X-Subscription-Token: {apiKey}"
        })
        BraveSearchResponse search(
                @Param("apiKey") String apiKey,
                @Param("query") String query,
                @Param("count") int count);
    }

    // Response DTOs
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BraveSearchResponse {
        private WebResults web;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WebResults {
        private List<WebResult> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WebResult {
        private String title;
        private String url;
        private String description;
    }
}
