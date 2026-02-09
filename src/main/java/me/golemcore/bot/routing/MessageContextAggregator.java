package me.golemcore.bot.routing;

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

import me.golemcore.bot.domain.component.MessageAggregator;
import me.golemcore.bot.domain.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects and aggregates fragmented user messages for improved skill routing.
 *
 * <p>
 * Users often split complex requests across multiple messages. This component
 * identifies fragmentation signals and combines related messages into a single
 * query for skill matching.
 *
 * <p>
 * Fragmentation is detected using multiple signals:
 * <ul>
 * <li>Message length - too short to be standalone (< 4 words)</li>
 * <li>Back-references - pronouns like "it", "this", "that"</li>
 * <li>Continuation markers - starts with "and", "also", "plus"</li>
 * <li>Capitalization - starts with lowercase letter</li>
 * <li>Incomplete endings - previous message ends with "...", ":", "-"</li>
 * <li>Time window - messages within 60 seconds</li>
 * </ul>
 *
 * <p>
 * A message is considered fragmented if it exhibits 2 or more signals.
 * Aggregation respects time windows to avoid combining unrelated messages.
 *
 * @since 1.0
 */
@Component
@Slf4j
public class MessageContextAggregator implements MessageAggregator {

    /**
     * Maximum number of recent messages to consider.
     */
    private static final int MAX_MESSAGES = 5;

    /**
     * Maximum time gap between messages to consider them related.
     */
    private static final Duration MAX_GAP = Duration.ofSeconds(60);

    /**
     * Minimum words for a message to be considered standalone.
     */
    private static final int MIN_WORDS_FOR_STANDALONE = 4;

    /**
     * Pattern for back-references to previous context.
     */
    private static final Pattern BACK_REFERENCE_PATTERN = Pattern.compile(
            "\\b(это|этот|эта|эти|его|её|их|там|тут|туда|оттуда|выше|ранее|" +
                    "такой|такая|такое|такие|этим|этой|этому|" +
                    "it|this|that|these|those|there|here|above|earlier|its|their)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Pattern for continuation markers at the start of message.
     */
    private static final Pattern CONTINUATION_START_PATTERN = Pattern.compile(
            "^\\s*(и\\s|а\\s|а также|плюс|ещё|еще|кстати|также|потом|затем|" +
                    "and\\s|also|plus|btw|then|additionally)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Pattern for incomplete endings (previous message might be unfinished).
     */
    private static final Pattern INCOMPLETE_ENDING_PATTERN = Pattern.compile(
            "(\\.\\.\\.|—|--|:|-|,)\\s*$");

    /**
     * Build aggregated query for skill routing.
     *
     * @param history
     *            full message history
     * @return aggregated query string for routing
     */
    public String buildRoutingQuery(List<Message> history) {
        log.debug("[Aggregator] Building routing query from {} messages in history", history.size());
        List<Message> userMessages = extractRecentUserMessages(history, MAX_MESSAGES);
        log.debug("[Aggregator] Found {} recent user messages", userMessages.size());

        if (userMessages.isEmpty()) {
            log.debug("[Aggregator] No user messages found, returning empty query");
            return "";
        }

        Message latest = userMessages.get(userMessages.size() - 1);
        String latestContent = latest.getContent();
        log.debug("[Aggregator] Latest message: '{}'", truncate(latestContent, 100));

        // Check if latest message is standalone
        if (isStandalone(latestContent, userMessages)) {
            log.debug("[Aggregator] Message is STANDALONE, using as-is");
            return latestContent;
        }

        // Aggregate related messages
        String aggregated = aggregateRelatedMessages(userMessages);

        if (!aggregated.equals(latestContent)) {
            log.debug("[Aggregator] Detected FRAGMENTED input, aggregated {} messages",
                    countAggregatedMessages(userMessages));
            log.debug("[Aggregator] Aggregated query: '{}'", truncate(aggregated, 150));
        }

        return aggregated;
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * Check if a message appears to be fragmented (part of a larger request).
     *
     * @param message
     *            the message to check
     * @param history
     *            recent message history
     * @return true if message appears fragmented
     */
    public boolean isFragmented(String message, List<Message> history) {
        return !isStandalone(message, extractRecentUserMessages(history, MAX_MESSAGES));
    }

    /**
     * Get aggregation analysis for debugging/logging.
     */
    public AggregationAnalysis analyze(List<Message> history) {
        List<Message> userMessages = extractRecentUserMessages(history, MAX_MESSAGES);

        if (userMessages.isEmpty()) {
            return new AggregationAnalysis(false, List.of(), "No user messages");
        }

        Message latest = userMessages.get(userMessages.size() - 1);
        String content = latest.getContent();

        List<String> reasons = new ArrayList<>();

        // Check various fragmentation signals
        if (isTooShort(content)) {
            reasons.add("too_short");
        }
        if (hasBackReference(content)) {
            reasons.add("has_back_reference");
        }
        if (startsWithContinuationMarker(content)) {
            reasons.add("starts_with_continuation");
        }
        if (startsWithLowercase(content)) {
            reasons.add("starts_lowercase");
        }
        if (userMessages.size() > 1) {
            Message prev = userMessages.get(userMessages.size() - 2);
            if (hasIncompleteEnding(prev.getContent())) {
                reasons.add("previous_incomplete");
            }
            if (isWithinTimeWindow(prev, latest)) {
                reasons.add("within_time_window");
            }
        }

        boolean isFragmented = !reasons.isEmpty() && reasons.size() >= 2;

        return new AggregationAnalysis(
                isFragmented,
                reasons,
                isFragmented ? "Fragmented: " + String.join(", ", reasons) : "Standalone");
    }

    private List<Message> extractRecentUserMessages(List<Message> history, int maxCount) {
        List<Message> userMessages = new ArrayList<>();

        // Iterate from end to get most recent
        for (int i = history.size() - 1; i >= 0 && userMessages.size() < maxCount; i--) {
            Message msg = history.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                userMessages.add(0, msg); // Add at beginning to maintain order
            }
        }

        return userMessages;
    }

    private boolean isStandalone(String content, List<Message> recentUserMessages) {
        // Multiple strong signals needed to consider it fragmented
        int fragmentationSignals = 0;

        // Too short
        if (isTooShort(content)) {
            fragmentationSignals++;
        }

        // Has back references
        if (hasBackReference(content)) {
            fragmentationSignals++;
        }

        // Starts with continuation marker
        if (startsWithContinuationMarker(content)) {
            fragmentationSignals += 2; // Strong signal
        }

        // Starts with lowercase
        if (startsWithLowercase(content)) {
            fragmentationSignals++;
        }

        // Check previous message
        if (recentUserMessages.size() > 1) {
            Message previous = recentUserMessages.get(recentUserMessages.size() - 2);
            Message current = recentUserMessages.get(recentUserMessages.size() - 1);

            // Previous message has incomplete ending
            if (hasIncompleteEnding(previous.getContent())) {
                fragmentationSignals++;
            }

            // Messages are close in time
            if (isWithinTimeWindow(previous, current)) {
                fragmentationSignals++;
            }
        }

        // Consider standalone if less than 2 fragmentation signals
        return fragmentationSignals < 2;
    }

    private String aggregateRelatedMessages(List<Message> userMessages) {
        if (userMessages.isEmpty()) {
            return "";
        }

        if (userMessages.size() == 1) {
            return userMessages.get(0).getContent();
        }

        StringBuilder aggregated = new StringBuilder();
        Message anchor = null; // First message of current "session"

        for (int i = 0; i < userMessages.size(); i++) {
            Message msg = userMessages.get(i);

            // Check if this starts a new session (time gap too large)
            if (anchor != null && !isWithinTimeWindow(anchor, msg)) {
                // Reset - start new session
                aggregated.setLength(0);
            }

            if (aggregated.length() > 0) {
                aggregated.append(" ");
            }
            aggregated.append(msg.getContent().trim());

            if (anchor == null) {
                anchor = msg;
            }
        }

        return aggregated.toString().trim();
    }

    private int countAggregatedMessages(List<Message> userMessages) {
        if (userMessages.size() <= 1) {
            return 1;
        }

        int count = 0;
        Message anchor = null;

        for (Message msg : userMessages) {
            if (anchor != null && !isWithinTimeWindow(anchor, msg)) {
                count = 0;
            }
            count++;
            if (anchor == null) {
                anchor = msg;
            }
        }

        return count;
    }

    private boolean isTooShort(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        int wordCount = content.trim().split("\\s+").length;
        return wordCount < MIN_WORDS_FOR_STANDALONE;
    }

    private boolean hasBackReference(String content) {
        return BACK_REFERENCE_PATTERN.matcher(content).find();
    }

    private boolean startsWithContinuationMarker(String content) {
        return CONTINUATION_START_PATTERN.matcher(content).find();
    }

    private boolean startsWithLowercase(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        char first = content.trim().charAt(0);
        // Only consider if it's a letter
        if (!Character.isLetter(first)) {
            return false;
        }
        return Character.isLowerCase(first);
    }

    private boolean hasIncompleteEnding(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return INCOMPLETE_ENDING_PATTERN.matcher(content).find();
    }

    private boolean isWithinTimeWindow(Message earlier, Message later) {
        Instant earlierTime = earlier.getTimestamp();
        Instant laterTime = later.getTimestamp();

        if (earlierTime == null || laterTime == null) {
            // If no timestamps, assume they're related (conservative)
            return true;
        }

        Duration gap = Duration.between(earlierTime, laterTime);
        return gap.compareTo(MAX_GAP) <= 0;
    }

}
