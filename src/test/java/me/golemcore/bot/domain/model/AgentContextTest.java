package me.golemcore.bot.domain.model;

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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextTest {

    @Test
    void addToolResultShouldInitializeMapWhenNull() {
        AgentContext ctx = AgentContext.builder().toolResults(null).build();

        ctx.addToolResult("tc1", ToolResult.success("ok"));

        assertNotNull(ctx.getToolResults());
        assertEquals("ok", ctx.getToolResults().get("tc1").getOutput());
    }

    @Test
    void setAttributeShouldInitializeMapWhenNull() {
        AgentContext ctx = AgentContext.builder().attributes(null).build();

        ctx.setAttribute("k", "v");

        assertNotNull(ctx.getAttributes());
        assertEquals("v", ctx.getAttribute("k"));
    }

    @Test
    void getAttributeShouldReturnNullWhenAttributesNull() {
        AgentContext ctx = AgentContext.builder().attributes(null).build();
        assertNull(ctx.getAttribute("missing"));
    }

    @Test
    void clearSkillTransitionRequestShouldSetNull() {
        SkillTransitionRequest req = SkillTransitionRequest.pipeline("next-skill");
        AgentContext ctx = AgentContext.builder().skillTransitionRequest(req).build();

        assertNotNull(ctx.getSkillTransitionRequest());
        ctx.clearSkillTransitionRequest();
        assertNull(ctx.getSkillTransitionRequest());
    }

    @Test
    void finalAnswerReadyDefaultShouldBeFalseAndCanBeSet() {
        AgentContext ctx = AgentContext.builder().build();
        assertFalse(ctx.isFinalAnswerReady());

        ctx.setFinalAnswerReady(true);
        assertTrue(ctx.isFinalAnswerReady());
    }

    @Test
    void builderDefaultsShouldInitializeCollections() {
        AgentContext ctx = AgentContext.builder().build();

        assertNotNull(ctx.getMessages());
        assertNotNull(ctx.getAvailableTools());
        assertNotNull(ctx.getActiveSkills());
        assertNotNull(ctx.getToolResults());
        assertNotNull(ctx.getAttributes());

        // Ensure they are mutable collections (we rely on mutation in systems)
        ctx.getAttributes().put("k", "v");
        assertEquals("v", ctx.getAttribute("k"));

        ctx.getToolResults().put("tc", ToolResult.success("ok"));
        assertEquals("ok", ctx.getToolResults().get("tc").getOutput());
    }

    @Test
    void builderShouldRespectProvidedMaps() {
        AgentContext ctx = AgentContext.builder()
                .attributes(new java.util.HashMap<>(Map.of("a", 1)))
                .toolResults(new java.util.HashMap<>(Map.of("tc1", ToolResult.success("ok"))))
                .build();

        assertEquals(1, ctx.<Integer>getAttribute("a"));
        assertEquals("ok", ctx.getToolResults().get("tc1").getOutput());
    }
}
