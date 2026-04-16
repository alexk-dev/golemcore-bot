package me.golemcore.bot.domain.system.toolloop.resilience;

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

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * L1 — Hot Retry with full jitter (AWS-style).
 *
 * <p>
 * Implements exponential backoff with full jitter to prevent thundering-herd
 * effects when multiple agent instances hit a failing provider simultaneously.
 * The jitter range is {@code [0, min(cap, base * 2^attempt)]}, which spreads
 * retry requests uniformly across the backoff window.
 *
 * <p>
 * This replaces the previous inline retry in both {@code Langchain4jAdapter}
 * (adapter-level) and {@code LlmCallPhase.scheduleRetry()} (tool-loop-level)
 * with a single, shared policy that fixes the cap bug (was 3s, now configurable
 * with a sane default of 60s).
 *
 * @see <a href=
 *      "https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">
 *      AWS: Exponential Backoff and Jitter</a>
 */
public class LlmRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(LlmRetryPolicy.class);

    /**
     * Returns true if the current attempt is within the retry budget.
     */
    public boolean shouldRetry(int attempt, RuntimeConfig.ResilienceConfig config) {
        return attempt < config.getHotRetryMaxAttempts();
    }

    /**
     * Computes the sleep duration with full jitter.
     *
     * <p>
     * Formula: {@code random(0, min(capMs, baseMs * 2^attempt))}
     *
     * @param attempt
     *            0-based attempt index
     * @param config
     *            resilience configuration with base delay and cap
     * @return delay in milliseconds (always >= 0)
     */
    public long computeDelay(int attempt, RuntimeConfig.ResilienceConfig config) {
        long baseMs = config.getHotRetryBaseDelayMs();
        long capMs = config.getHotRetryCapMs();
        long exponential = (long) (baseMs * Math.pow(2.0, Math.max(0, attempt)));
        long ceiling = Math.min(capMs, exponential);
        long delay = ThreadLocalRandom.current().nextLong(0, Math.max(1, ceiling));
        return delay;
    }

    /**
     * Sleeps for the given duration, respecting thread interruption.
     */
    public void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[Resilience] L1 retry sleep interrupted");
        }
    }
}
