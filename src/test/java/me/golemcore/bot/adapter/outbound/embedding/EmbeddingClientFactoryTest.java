package me.golemcore.bot.adapter.outbound.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class EmbeddingClientFactoryTest {

    private final OpenAiCompatibleEmbeddingClient openAiCompatibleEmbeddingClient = mock(
            OpenAiCompatibleEmbeddingClient.class);
    private final OllamaEmbeddingClient ollamaEmbeddingClient = mock(OllamaEmbeddingClient.class);
    private final EmbeddingClientFactory factory = new EmbeddingClientFactory(openAiCompatibleEmbeddingClient,
            ollamaEmbeddingClient);

    @Test
    void shouldResolveOpenAiCompatibleAliasesCaseInsensitively() {
        assertThat(factory.resolve("openai")).isSameAs(openAiCompatibleEmbeddingClient);
        assertThat(factory.resolve(" openai_compatible ")).isSameAs(openAiCompatibleEmbeddingClient);
        assertThat(factory.resolve("OpenAI-Compatible")).isSameAs(openAiCompatibleEmbeddingClient);
    }

    @Test
    void shouldResolveOllamaProviderCaseInsensitively() {
        assertThat(factory.resolve("ollama")).isSameAs(ollamaEmbeddingClient);
        assertThat(factory.resolve(" Ollama ")).isSameAs(ollamaEmbeddingClient);
    }

    @Test
    void shouldRejectUnsupportedProvider() {
        assertThatThrownBy(() -> factory.resolve("unsupported"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported embedding provider: unsupported");
    }

    @Test
    void shouldRejectMissingProvider() {
        assertThatThrownBy(() -> factory.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported embedding provider: null");
    }
}
