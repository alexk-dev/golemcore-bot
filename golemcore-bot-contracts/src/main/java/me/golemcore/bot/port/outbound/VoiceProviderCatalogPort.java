package me.golemcore.bot.port.outbound;

public interface VoiceProviderCatalogPort {

    boolean hasSttProvider(String providerId);

    boolean hasTtsProvider(String providerId);

    String firstSttProviderId();

    String firstTtsProviderId();
}
