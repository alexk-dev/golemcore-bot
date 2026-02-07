package me.golemcore.bot.domain.loop;

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

import me.golemcore.bot.domain.model.AgentContext;

/**
 * ThreadLocal holder for AgentContext enabling tools to access the current
 * processing context without explicit parameter passing. Critical for tools
 * that need to interact with the agent loop (e.g., SkillTransitionTool). Must
 * be used carefully as ThreadLocal values don't propagate to async operations
 * (CompletableFuture.supplyAsync).
 */
public class AgentContextHolder {

    private static final ThreadLocal<AgentContext> CONTEXT = new ThreadLocal<>();

    public static void set(AgentContext ctx) {
        CONTEXT.set(ctx);
    }

    public static AgentContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    private AgentContextHolder() {
    }
}
