package me.golemcore.bot.domain.component;

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
import me.golemcore.bot.port.outbound.BrowserPort;

import java.util.concurrent.CompletableFuture;

/**
 * Component providing headless browser capabilities for web page interaction.
 * Wraps a {@link BrowserPort} to enable navigation, content extraction, and
 * screenshot capture. Used by BrowserTool to expose browser operations to the
 * LLM as executable tools.
 */
public interface BrowserComponent extends Component {

    @Override
    default String getComponentType() {
        return "browser";
    }

    /**
     * Returns the underlying browser port implementation.
     *
     * @return the browser port
     */
    BrowserPort getBrowserPort();

    /**
     * Navigates to the specified URL and returns page information.
     *
     * @param url
     *            the URL to navigate to
     * @return a future containing the browser page
     */
    default CompletableFuture<BrowserPage> navigate(String url) {
        return getBrowserPort().navigate(url);
    }

    /**
     * Extracts visible text content from the specified URL.
     *
     * @param url
     *            the URL to extract text from
     * @return a future containing the page text
     */
    default CompletableFuture<String> getText(String url) {
        return getBrowserPort().getText(url);
    }

    /**
     * Retrieves raw HTML content from the specified URL.
     *
     * @param url
     *            the URL to retrieve HTML from
     * @return a future containing the HTML source
     */
    default CompletableFuture<String> getHtml(String url) {
        return getBrowserPort().getHtml(url);
    }

    /**
     * Captures a screenshot of the specified URL.
     *
     * @param url
     *            the URL to screenshot
     * @return a future containing the screenshot bytes (PNG format)
     */
    default CompletableFuture<byte[]> screenshot(String url) {
        return getBrowserPort().screenshot(url);
    }

    /**
     * Checks whether the browser is available and functional.
     *
     * @return true if browser operations can be performed
     */
    default boolean isAvailable() {
        return getBrowserPort().isAvailable();
    }
}
