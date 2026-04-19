package me.golemcore.bot.infrastructure.event;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Event bus implementation using Spring's ApplicationEventPublisher.
 *
 * <p>
 * Provides a simple facade for publishing domain events within the application.
 * Events are delivered synchronously by default to all registered Spring
 * {@code @EventListener} methods.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * eventBus.publish(new GoalCompletedEvent(goal));
 * }</pre>
 *
 * <p>
 * Listeners can receive events by annotating methods with
 * {@code @EventListener}.
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpringEventBus {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Publish an event.
     */
    public void publish(Object event) {
        log.debug("Publishing event: {}", event.getClass().getSimpleName());
        eventPublisher.publishEvent(event);
    }
}
