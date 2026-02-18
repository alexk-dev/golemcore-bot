package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpLlmAdapterTest {

    private NoOpLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NoOpLlmAdapter();
    }

    @Test
    void shouldReturnPlaceholderFromChat() throws Exception {
        LlmResponse response = adapter.chat(LlmRequest.builder().build()).get();

        assertEquals("[No LLM configured]", response.getContent());
        assertEquals("none", response.getModel());
        assertEquals("stop", response.getFinishReason());
        assertTrue(response.isComplete());
        assertFalse(response.hasToolCalls());
        assertNotNull(response.getUsage());
        assertEquals(0, response.getUsage().getTotalTokens());
    }

    @Test
    void shouldReturnSinglePlaceholderChunkFromStream() {
        StepVerifier.create(adapter.chatStream(LlmRequest.builder().build()))
                .assertNext(chunk -> {
                    assertEquals("[No LLM configured]", chunk.getText());
                    assertTrue(chunk.isDone());
                })
                .verifyComplete();
    }
}
