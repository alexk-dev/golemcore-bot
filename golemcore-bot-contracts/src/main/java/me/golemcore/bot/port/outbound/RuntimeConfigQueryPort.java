package me.golemcore.bot.port.outbound;

import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Query/update bridge for runtime configuration access from capability modules.
 */
public interface RuntimeConfigQueryPort {

    RuntimeConfig getRuntimeConfig();

    RuntimeConfig getRuntimeConfigForApi();

    boolean isTelegramEnabled();

    String getTelegramToken();

    List<String> getTelegramAllowedUsers();

    boolean isVoiceEnabled();

    String getVoiceApiKey();

    String getVoiceId();

    String getTtsModelId();

    String getSttModelId();

    float getVoiceSpeed();

    boolean isTelegramTranscribeIncomingEnabled();

    String getSttProvider();

    String getTtsProvider();

    String getWhisperSttUrl();

    String getWhisperSttApiKey();

    boolean isToolConfirmationEnabled();

    int getToolConfirmationTimeoutSeconds();

    RuntimeConfig.InviteCode generateInviteCode();

    boolean revokeInviteCode(String code);

    boolean redeemInviteCode(String code, String userId);

    boolean removeTelegramAllowedUser(String userId);
}
