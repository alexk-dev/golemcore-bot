package me.golemcore.bot.port.outbound;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public interface ProviderModelDiscoveryPort {

    DiscoveryResponse discover(DiscoveryRequest request);

    enum AuthMode {
        BEARER, ANTHROPIC, GOOGLE
    }

    enum DocumentKind {
        OPENAI_LIKE, GEMINI
    }

    record DiscoveryRequest(URI uri, Duration timeout, String apiKey, String userAgent, AuthMode authMode) {
    }

    record DiscoveryDocument(
            DocumentKind kind,
            String id,
            String displayName,
            String ownedBy,
            String provider,
            String type,
            List<String> inputModalities,
            String modality,
            List<String> supportedParameters,
            Integer contextLength,
            Integer topProviderContextLength,
            String publisher,
            List<String> supportedGenerationMethods) {
    }

    record DiscoveryResponse(int statusCode, List<DiscoveryDocument> documents) {
    }
}
