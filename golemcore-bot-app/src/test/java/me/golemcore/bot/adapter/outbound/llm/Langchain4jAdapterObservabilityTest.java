package me.golemcore.bot.adapter.outbound.llm;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.ModelConfigPort;
import me.golemcore.bot.port.outbound.ToolArtifactReadPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Langchain4jAdapterObservabilityTest {

    private static final String OPENAI = "openai";
    private static final String MODEL = "openai/gpt-4.1";

    private RuntimeConfigService runtimeConfigService;
    private ModelConfigPort modelConfig;
    private ToolArtifactReadPort toolArtifactReadPort;
    private ListAppender<ILoggingEvent> appender;
    private Logger adapterLogger;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelConfig = mock(ModelConfigPort.class);
        toolArtifactReadPort = mock(ToolArtifactReadPort.class);
        when(modelConfig.supportsTemperature(anyString())).thenReturn(true);
        when(modelConfig.supportsVision(anyString())).thenReturn(false);
        when(modelConfig.getProvider(anyString())).thenReturn(OPENAI);
        when(modelConfig.isReasoningRequired(anyString())).thenReturn(false);
        when(modelConfig.getAllModels()).thenReturn(Map.of());
        when(runtimeConfigService.getTemperatureForModel(any(), any())).thenReturn(0.7);
        when(runtimeConfigService.getBalancedModel()).thenReturn(MODEL);
        when(runtimeConfigService.getBalancedModelReasoning()).thenReturn("medium");
        when(runtimeConfigService.getLlmProviderConfig(anyString()))
                .thenReturn(RuntimeConfig.LlmProviderConfig.builder().legacyApi(true).build());

        adapterLogger = (Logger) LoggerFactory.getLogger(Langchain4jAdapter.class);
        appender = new ListAppender<>();
        appender.start();
        adapterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        adapterLogger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void shouldPropagateCallerMdcIntoWorkerThreadObservedAtRetrySleep() throws Exception {
        AtomicReference<String> observedTrace = new AtomicReference<>();
        AtomicReference<String> observedSpan = new AtomicReference<>();

        Langchain4jAdapter adapter = new Langchain4jAdapter(runtimeConfigService, modelConfig, toolArtifactReadPort) {
            @Override
            protected void sleepBeforeRetry(long backoffMs) {
                observedTrace.set(MDC.get("trace"));
                observedSpan.set(MDC.get("span"));
            }
        };

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat((List<ChatMessage>) any()))
                .thenThrow(new RuntimeException("HTTP 429 Too Many Requests"))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .finishReason(FinishReason.STOP)
                        .build());
        ReflectionTestUtils.setField(adapter, "chatModel", chatModel);
        ReflectionTestUtils.setField(adapter, "currentModel", MODEL);
        ReflectionTestUtils.setField(adapter, "initialized", true);

        MDC.put("trace", "trace-propagation-123");
        MDC.put("span", "span-propagation-456");

        LlmRequest request = LlmRequest.builder()
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        LlmResponse response = adapter.chat(request).get();

        assertEquals("ok", response.getContent());
        assertEquals("trace-propagation-123", observedTrace.get(),
                "MDC trace must be propagated into the ForkJoinPool worker running chat()");
        assertEquals("span-propagation-456", observedSpan.get(),
                "MDC span must be propagated into the ForkJoinPool worker running chat()");
    }

    @Test
    void shouldIncludeCallerTagAndModelInRateLimitWarnLog() throws Exception {
        Langchain4jAdapter adapter = new Langchain4jAdapter(runtimeConfigService, modelConfig, toolArtifactReadPort) {
            @Override
            protected void sleepBeforeRetry(long backoffMs) {
                // fast retry
            }
        };

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat((List<ChatMessage>) any()))
                .thenThrow(new RuntimeException("HTTP 429 Too Many Requests"))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok"))
                        .finishReason(FinishReason.STOP)
                        .build());
        ReflectionTestUtils.setField(adapter, "chatModel", chatModel);
        ReflectionTestUtils.setField(adapter, "currentModel", MODEL);
        ReflectionTestUtils.setField(adapter, "initialized", true);

        LlmRequest request = LlmRequest.builder()
                .callerTag("follow_through")
                .messages(List.of(Message.builder().role("user").content("hi").build()))
                .build();

        adapter.chat(request).get();

        ILoggingEvent warn = findRateLimitWarn();
        assertNotNull(warn, "Rate-limit warn log must have been emitted");
        String rendered = warn.getFormattedMessage();
        assertTrue(rendered.contains("follow_through"),
                "Rate-limit warn must include caller tag for triage. Was: " + rendered);
        assertTrue(rendered.contains(MODEL),
                "Rate-limit warn must still include model id. Was: " + rendered);
    }

    private ILoggingEvent findRateLimitWarn() {
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel() == Level.WARN && event.getFormattedMessage().contains("Rate limit hit")) {
                return event;
            }
        }
        return null;
    }
}
