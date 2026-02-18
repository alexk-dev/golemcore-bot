package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Decorator around {@link LlmPort} that records token usage via
 * {@link UsageTrackingPort} after each chat completion call.
 */
class UsageTrackingLlmPortDecorator implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(UsageTrackingLlmPortDecorator.class);

    private final LlmPort delegate;
    private final UsageTrackingPort usageTracker;

    UsageTrackingLlmPortDecorator(LlmPort delegate, UsageTrackingPort usageTracker) {
        this.delegate = delegate;
        this.usageTracker = usageTracker;
    }

    @Override
    public String getProviderId() {
        return delegate.getProviderId();
    }

    @Override
    public CompletableFuture<LlmResponse> chat(LlmRequest request) {
        Instant start = Instant.now();
        return delegate.chat(request).thenApply(response -> {
            recordUsage(request, response, start);
            return response;
        });
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        return delegate.chatStream(request);
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }

    @Override
    public List<String> getSupportedModels() {
        return delegate.getSupportedModels();
    }

    @Override
    public String getCurrentModel() {
        return delegate.getCurrentModel();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    private void recordUsage(LlmRequest request, LlmResponse response, Instant start) {
        if (response == null || response.getUsage() == null) {
            return;
        }
        try {
            LlmUsage usage = response.getUsage();
            usage.setLatency(Duration.between(start, Instant.now()));
            usage.setTimestamp(Instant.now());
            usage.setSessionId(request.getSessionId());

            String model = response.getModel() != null ? response.getModel() : request.getModel();
            usage.setModel(model);

            String providerId = delegate.getProviderId();
            usage.setProviderId(providerId);

            usageTracker.recordUsage(providerId, model, usage);
        } catch (Exception e) { // NOSONAR
            log.warn("[UsageTracking] Failed to record usage: {}", e.getMessage());
        }
    }
}
