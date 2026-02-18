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

import me.golemcore.bot.domain.component.BrowserComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.BrowserPage;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tool for web browsing using headless browser.
 *
 * <p>
 * Delegates to {@link BrowserComponent} (typically
 * {@link me.golemcore.bot.adapter.outbound.browser.PlaywrightAdapter}) for
 * headless browser operations.
 *
 * <p>
 * Modes:
 * <ul>
 * <li>text - Extract page text content (default, 10K char limit)
 * <li>html - Extract raw HTML
 * <li>screenshot - Take PNG screenshot and send to user
 * </ul>
 *
 * <p>
 * Security:
 * <ul>
 * <li>Only http:// and https:// URLs allowed
 * <li>Blocks javascript:, data:, file:// schemes
 * <li>30-second timeout per page
 * </ul>
 *
 * <p>
 * Screenshots are sent as {@link Attachment} and queued for delivery by
 * {@link me.golemcore.bot.domain.system.ResponseRoutingSystem}.
 *
 * @see BrowserComponent
 * @see me.golemcore.bot.adapter.outbound.browser.PlaywrightAdapter
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrowserTool implements ToolComponent {

    private static final String PARAM_URL = "url";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_OBJECT = "object";

    private final BrowserComponent browserComponent;
    private final RuntimeConfigService runtimeConfigService;

    private static final int MAX_TEXT_LENGTH = 10000;
    private static final long TIMEOUT_SECONDS = 30;

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("browse")
                .description("Browse a web page and extract its content. Returns the page title and text content.")
                .inputSchema(Map.of(
                        "type", TYPE_OBJECT,
                        "properties", Map.of(
                                PARAM_URL, Map.of(
                                        "type", TYPE_STRING,
                                        "description", "The URL to browse"),
                                "mode", Map.of(
                                        "type", TYPE_STRING,
                                        "description", "What to extract: 'text' (default), 'html', or 'screenshot'",
                                        "enum", java.util.List.of("text", "html", "screenshot"))),
                        "required", java.util.List.of(PARAM_URL)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String url = (String) parameters.get(PARAM_URL);
            if (url == null || url.isBlank()) {
                return ToolResult.failure("URL is required");
            }

            // Only allow http/https schemes to prevent file://, javascript:, data: attacks
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (url.contains("://") || url.startsWith("javascript:") || url.startsWith("data:")
                        || url.startsWith("file:")) {
                    return ToolResult.failure("Only http and https URLs are allowed");
                }
                url = "https://" + url;
            }

            String mode = (String) parameters.getOrDefault("mode", "text");

            try {
                return switch (mode) {
                case "html" -> executeHtml(url);
                case "screenshot" -> executeScreenshot(url);
                default -> executeText(url);
                };
            } catch (Exception e) {
                log.error("Browser tool failed for URL: {}", url, e);
                return ToolResult.failure("Failed to browse page: " + e.getMessage());
            }
        });
    }

    private ToolResult executeText(String url) throws Exception {
        BrowserPage page = browserComponent.navigate(url)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String text = page.getText();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n... (truncated)";
        }

        String output = String.format("**%s**%n%nURL: %s%n%n%s",
                page.getTitle(), page.getUrl(), text);

        return ToolResult.success(output, Map.of(
                "title", page.getTitle(),
                PARAM_URL, page.getUrl()));
    }

    private ToolResult executeHtml(String url) throws Exception {
        String html = browserComponent.getHtml(url)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (html != null && html.length() > MAX_TEXT_LENGTH * 2) {
            html = html.substring(0, MAX_TEXT_LENGTH * 2) + "\n... (truncated)";
        }

        return ToolResult.success(html);
    }

    private ToolResult executeScreenshot(String url) throws Exception {
        byte[] screenshot = browserComponent.screenshot(url)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String base64 = java.util.Base64.getEncoder().encodeToString(screenshot);

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(screenshot)
                .filename("screenshot.png")
                .mimeType("image/png")
                .caption("Screenshot of " + url)
                .build();

        return ToolResult.success(
                "Screenshot captured (" + screenshot.length + " bytes)",
                Map.of("attachment", attachment, "screenshot_base64", base64, "format", "png"));
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfigService.isBrowserEnabled();
    }
}
