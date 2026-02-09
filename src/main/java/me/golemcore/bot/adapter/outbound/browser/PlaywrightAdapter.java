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

package me.golemcore.bot.adapter.outbound.browser;

import me.golemcore.bot.domain.component.BrowserComponent;
import me.golemcore.bot.domain.model.BrowserPage;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.BrowserPort;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Playwright implementation of BrowserPort for headless browser operations.
 *
 * <p>
 * This adapter provides headless browser functionality using Microsoft
 * Playwright. Used by {@link me.golemcore.bot.tools.BrowserTool} to enable LLM
 * agents to navigate web pages, extract content, and take screenshots.
 *
 * <p>
 * Features:
 * <ul>
 * <li>Navigate to URLs with timeout control
 * <li>Extract page content (HTML, text, title)
 * <li>Take screenshots (PNG format)
 * <li>Custom user agent configuration
 * </ul>
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code bot.browser.enabled} - Enable/disable browser
 * <li>{@code bot.browser.headless} - Run in headless mode
 * <li>{@code bot.browser.timeout} - Page load timeout (ms)
 * <li>{@code bot.browser.user-agent} - Custom user agent
 * </ul>
 *
 * <p>
 * Lazy initialization: Browser is only launched on first use.
 *
 * @see me.golemcore.bot.port.outbound.BrowserPort
 * @see me.golemcore.bot.tools.BrowserTool
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaywrightAdapter implements BrowserPort, BrowserComponent {

    private final BotProperties properties;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private volatile boolean initialized = false;

    @SuppressWarnings("PMD.CloseResource")
    private synchronized void ensureInitialized() {
        if (initialized || !properties.getBrowser().isEnabled()) {
            return;
        }

        Playwright pw = null;
        Browser br = null;
        try {
            pw = Playwright.create();
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(properties.getBrowser().isHeadless());
            br = pw.chromium().launch(launchOptions);

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
            String userAgent = properties.getBrowser().getUserAgent();
            if (userAgent != null && !userAgent.isBlank()) {
                contextOptions.setUserAgent(userAgent);
            }

            this.context = br.newContext(contextOptions);
            this.browser = br;
            this.playwright = pw;
            initialized = true;

            log.info("Playwright browser initialized (headless: {})", properties.getBrowser().isHeadless());
        } catch (Exception e) {
            log.warn("Failed to initialize Playwright: {}", e.getMessage());
            // Clean up partially created resources to prevent process leaks
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ex) {
                    log.trace("Error closing browser resource: {}", ex.getMessage());
                }
            }
            if (pw != null) {
                try {
                    pw.close();
                } catch (Exception ex) {
                    log.trace("Error closing browser resource: {}", ex.getMessage());
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        close();
    }

    @Override
    @SuppressWarnings("PMD.UseTryWithResources")
    public CompletableFuture<BrowserPage> navigate(String url) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            if (!isAvailable()) {
                throw new RuntimeException("Browser not available or disabled");
            }

            Page page = context.newPage();
            try {
                int timeout = properties.getBrowser().getTimeout();
                page.setDefaultTimeout(timeout);

                page.navigate(url);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                return BrowserPage.builder()
                        .url(page.url())
                        .title(page.title())
                        .html(page.content())
                        .text(extractText(page))
                        .build();
            } finally {
                page.close();
            }
        });
    }

    @Override
    public CompletableFuture<String> getHtml(String url) {
        return navigate(url).thenApply(BrowserPage::getHtml);
    }

    @Override
    public CompletableFuture<String> getText(String url) {
        return navigate(url).thenApply(BrowserPage::getText);
    }

    @Override
    @SuppressWarnings("PMD.UseTryWithResources")
    public CompletableFuture<byte[]> screenshot(String url) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            if (!isAvailable()) {
                throw new RuntimeException("Browser not available or disabled");
            }

            Page page = context.newPage();
            try {
                int timeout = properties.getBrowser().getTimeout();
                page.setDefaultTimeout(timeout);

                page.navigate(url);
                page.waitForLoadState(LoadState.NETWORKIDLE);

                return page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
            } finally {
                page.close();
            }
        });
    }

    @Override
    @SuppressWarnings("PMD.UseTryWithResources")
    public CompletableFuture<String> evaluate(String url, String script) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            if (!isAvailable()) {
                throw new RuntimeException("Browser not available or disabled");
            }

            Page page = context.newPage();
            try {
                int timeout = properties.getBrowser().getTimeout();
                page.setDefaultTimeout(timeout);

                page.navigate(url);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                Object result = page.evaluate(script);
                return result != null ? result.toString() : null;
            } finally {
                page.close();
            }
        });
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                context.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            log.info("Playwright browser closed");
        } catch (Exception e) {
            log.error("Error closing Playwright browser", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return properties.getBrowser().isEnabled() && browser != null && browser.isConnected();
    }

    @Override
    public BrowserPort getBrowserPort() {
        return this;
    }

    private String extractText(Page page) {
        try {
            // Remove script and style elements, then get text content
            return page.evaluate("""
                    (() => {
                        const clone = document.body.cloneNode(true);
                        const scripts = clone.querySelectorAll('script, style, noscript');
                        scripts.forEach(el => el.remove());
                        return clone.innerText;
                    })()
                    """).toString();
        } catch (Exception e) {
            log.warn("Failed to extract text from page", e);
            return page.textContent("body");
        }
    }
}
