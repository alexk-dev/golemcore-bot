package me.golemcore.bot.port.outbound;

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

import me.golemcore.bot.domain.model.BrowserPage;

import java.util.concurrent.CompletableFuture;

/**
 * Port for headless browser automation using Playwright. Provides web scraping,
 * screenshot capture, and JavaScript execution capabilities.
 */
public interface BrowserPort {

    /**
     * Navigate to a URL and return the page.
     */
    CompletableFuture<BrowserPage> navigate(String url);

    /**
     * Get HTML of a page.
     */
    CompletableFuture<String> getHtml(String url);

    /**
     * Get text content (without HTML tags).
     */
    CompletableFuture<String> getText(String url);

    /**
     * Take a screenshot of a page.
     */
    CompletableFuture<byte[]> screenshot(String url);

    /**
     * Execute JavaScript on a page.
     */
    CompletableFuture<String> evaluate(String url, String script);

    /**
     * Close the browser.
     */
    void close();

    /**
     * Check if browser is available.
     */
    boolean isAvailable();
}
