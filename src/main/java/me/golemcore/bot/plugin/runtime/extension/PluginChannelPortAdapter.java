package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.port.inbound.ChannelPort;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Adapts a plugin API channel into the host channel contract.
 */
public final class PluginChannelPortAdapter implements ChannelPort {

    private final me.golemcore.plugin.api.extension.port.inbound.ChannelPort delegate;
    private final PluginExtensionApiMapper mapper;

    public PluginChannelPortAdapter(me.golemcore.plugin.api.extension.port.inbound.ChannelPort delegate,
            PluginExtensionApiMapper mapper) {
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public String getChannelType() {
        return delegate.getChannelType();
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        return delegate.sendMessage(chatId, content);
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
        return delegate.sendMessage(chatId, content, hints);
    }

    @Override
    public CompletableFuture<Void> sendMessage(me.golemcore.bot.domain.model.Message message) {
        return delegate.sendMessage(mapper.toPluginMessage(message));
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        return delegate.sendVoice(chatId, voiceData);
    }

    @Override
    public boolean isVoiceResponseEnabled() {
        return delegate.isVoiceResponseEnabled();
    }

    @Override
    public CompletableFuture<Void> sendProgressUpdate(String chatId, ProgressUpdate update) {
        return delegate.sendProgressUpdate(chatId, mapper.toPluginProgressUpdate(update));
    }

    @Override
    public boolean isAuthorized(String senderId) {
        return delegate.isAuthorized(senderId);
    }

    @Override
    public void onMessage(Consumer<me.golemcore.bot.domain.model.Message> handler) {
        delegate.onMessage(message -> handler.accept(mapper.toHostMessage(message)));
    }

    @Override
    public CompletableFuture<Void> sendPhoto(String chatId, byte[] imageData, String filename, String caption) {
        return delegate.sendPhoto(chatId, imageData, filename, caption);
    }

    @Override
    public CompletableFuture<Void> sendDocument(String chatId, byte[] fileData, String filename, String caption) {
        return delegate.sendDocument(chatId, fileData, filename, caption);
    }

    @Override
    public void showTyping(String chatId) {
        delegate.showTyping(chatId);
    }
}
