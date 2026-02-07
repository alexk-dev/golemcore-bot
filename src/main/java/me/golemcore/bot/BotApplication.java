package me.golemcore.bot;

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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main application class for Java AI Bot.
 *
 * <p>
 * Java AI Bot is an extensible AI assistant framework built with Spring Boot
 * 3.4.2, supporting multiple LLM providers, intelligent skill routing, and
 * powerful tool execution.
 *
 * <h2>Key Features</h2>
 * <ul>
 * <li><b>Multi-LLM Support</b> - OpenAI (GPT-4o, o1, gpt-5.x) and Anthropic
 * (Claude) via langchain4j</li>
 * <li><b>Skill Routing</b> - Hybrid semantic search + LLM classifier for
 * intelligent request routing</li>
 * <li><b>Tool Execution</b> - FileSystem, Shell, Browser, Weather, DateTime
 * tools with security sandboxing</li>
 * <li><b>MCP Client</b> - Model Context Protocol integration for external tool
 * providers</li>
 * <li><b>RAG Integration</b> - LightRAG for contextual memory retrieval</li>
 * <li><b>Autonomous Mode</b> - Goal-driven task execution with diary and
 * progress tracking</li>
 * <li><b>Multi-Channel</b> - Telegram support with voice message transcription
 * (Whisper)</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <p>
 * Hexagonal architecture (Ports & Adapters) with ECS-inspired agent loop:
 *
 * <pre>
 * Input Layer        → TelegramAdapter, CommandRouter
 * Domain Layer       → AgentLoop, Systems Pipeline, Services
 * Infrastructure     → LLM/Storage/Browser/Voice Adapters
 * </pre>
 *
 * <h2>Configuration</h2>
 * <p>
 * All configuration via {@code application.properties} under {@code bot.*}
 * prefix. See {@code docs/CONFIGURATION.md} for details.
 *
 * @version 1.0
 * @since 1.0
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class BotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BotApplication.class, args);
    }

}
