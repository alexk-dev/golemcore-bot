package me.golemcore.bot.plugin.runtime.extension;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Exposes host ports under the neutral plugin extension API namespace.
 */
@Configuration(proxyBeanMethods = false)
public class PluginExtensionApiConfiguration {

    @Bean
    public me.golemcore.plugin.api.extension.port.inbound.CommandPort pluginCommandPort(
            me.golemcore.bot.port.inbound.CommandPort delegate,
            PluginExtensionApiMapper mapper) {
        return new me.golemcore.plugin.api.extension.port.inbound.CommandPort() {
            @Override
            public CompletableFuture<CommandResult> execute(String command, List<String> args,
                    java.util.Map<String, Object> context) {
                return delegate.execute(command, args, context)
                        .thenApply(mapper::toPluginCommandResult);
            }

            @Override
            public boolean hasCommand(String command) {
                return delegate.hasCommand(command);
            }

            @Override
            public List<CommandDefinition> listCommands() {
                return mapper.toPluginCommandDefinitions(delegate.listCommands());
            }
        };
    }

    @Bean
    public me.golemcore.plugin.api.extension.port.outbound.SessionPort pluginSessionPort(
            me.golemcore.bot.port.outbound.SessionPort delegate,
            PluginExtensionApiMapper mapper) {
        return new me.golemcore.plugin.api.extension.port.outbound.SessionPort() {
            @Override
            public me.golemcore.plugin.api.extension.model.AgentSession getOrCreate(String channelType, String chatId) {
                return mapper.toPluginAgentSession(delegate.getOrCreate(channelType, chatId));
            }

            @Override
            public Optional<me.golemcore.plugin.api.extension.model.AgentSession> get(String sessionId) {
                return delegate.get(sessionId).map(mapper::toPluginAgentSession);
            }

            @Override
            public void save(me.golemcore.plugin.api.extension.model.AgentSession session) {
                delegate.save(mapper.toHostAgentSession(session));
            }

            @Override
            public void delete(String sessionId) {
                delegate.delete(sessionId);
            }

            @Override
            public void clearMessages(String sessionId) {
                delegate.clearMessages(sessionId);
            }

            @Override
            public int compactMessages(String sessionId, int keepLast) {
                return delegate.compactMessages(sessionId, keepLast);
            }

            @Override
            public int compactWithSummary(String sessionId, int keepLast,
                    me.golemcore.plugin.api.extension.model.Message summaryMessage) {
                return delegate.compactWithSummary(sessionId, keepLast, mapper.toHostMessage(summaryMessage));
            }

            @Override
            public List<me.golemcore.plugin.api.extension.model.Message> getMessagesToCompact(String sessionId,
                    int keepLast) {
                return mapper.toPluginMessages(delegate.getMessagesToCompact(sessionId, keepLast));
            }

            @Override
            public int getMessageCount(String sessionId) {
                return delegate.getMessageCount(sessionId);
            }

            @Override
            public List<me.golemcore.plugin.api.extension.model.AgentSession> listAll() {
                return mapper.toPluginAgentSessions(delegate.listAll());
            }

            @Override
            public List<me.golemcore.plugin.api.extension.model.AgentSession> listByChannelType(String channelType) {
                return mapper.toPluginAgentSessions(delegate.listByChannelType(channelType));
            }

            @Override
            public List<me.golemcore.plugin.api.extension.model.AgentSession> listByChannelTypeAndTransportChatId(
                    String channelType,
                    String transportChatId) {
                return mapper.toPluginAgentSessions(
                        delegate.listByChannelTypeAndTransportChatId(channelType, transportChatId));
            }
        };
    }

    @Bean
    public me.golemcore.plugin.api.extension.port.outbound.VoicePort pluginExtensionVoicePort(
            me.golemcore.bot.port.outbound.VoicePort delegate,
            PluginExtensionApiMapper mapper) {
        return new me.golemcore.plugin.api.extension.port.outbound.VoicePort() {
            @Override
            public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData,
                    me.golemcore.plugin.api.extension.model.AudioFormat format) {
                try {
                    return adaptVoiceFuture(
                            delegate.transcribe(audioData, mapper.toHostAudioFormat(format)),
                            mapper::toPluginTranscriptionResult);
                } catch (me.golemcore.bot.port.outbound.VoicePort.QuotaExceededException ex) {
                    throw new me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException(
                            ex.getMessage());
                }
            }

            @Override
            public CompletableFuture<byte[]> synthesize(String text, VoiceConfig config) {
                try {
                    return adaptVoiceFuture(
                            delegate.synthesize(text, mapper.toHostVoiceConfig(config)),
                            bytes -> bytes);
                } catch (me.golemcore.bot.port.outbound.VoicePort.QuotaExceededException ex) {
                    throw new me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException(
                            ex.getMessage());
                }
            }

            @Override
            public boolean isAvailable() {
                return delegate.isAvailable();
            }

            private <S, T> CompletableFuture<T> adaptVoiceFuture(
                    CompletableFuture<S> source,
                    java.util.function.Function<S, T> successMapper) {
                CompletableFuture<T> mapped = new CompletableFuture<>();
                source.whenComplete((value, error) -> {
                    if (error != null) {
                        mapped.completeExceptionally(translateHostVoiceFailure(error));
                        return;
                    }
                    try {
                        mapped.complete(successMapper.apply(value));
                    } catch (RuntimeException ex) {
                        mapped.completeExceptionally(ex);
                    }
                });
                return mapped;
            }

            private Throwable translateHostVoiceFailure(Throwable error) {
                Throwable cause = unwrap(error);
                if (cause instanceof me.golemcore.bot.port.outbound.VoicePort.QuotaExceededException ex) {
                    return new me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException(
                            ex.getMessage());
                }
                return cause;
            }
        };
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completionException && completionException.getCause() != null) {
            return unwrap(completionException.getCause());
        }
        return error;
    }
}
