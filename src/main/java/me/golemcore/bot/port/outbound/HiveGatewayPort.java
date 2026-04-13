package me.golemcore.bot.port.outbound;

import java.util.List;
import java.util.Set;
import me.golemcore.bot.domain.model.hive.HiveAuthSession;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.domain.model.hive.HiveCardSummary;
import me.golemcore.bot.domain.model.hive.HiveCreateCardRequest;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import me.golemcore.bot.domain.model.hive.HiveThreadMessage;

public interface HiveGatewayPort {

    HiveAuthSession registerGolem(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels);

    HiveAuthSession rotateSession(String serverUrl, String golemId, String refreshToken);

    void sendHeartbeat(
            String serverUrl,
            String golemId,
            String accessToken,
            String status,
            String healthSummary,
            String lastErrorSummary,
            Long uptimeSeconds);

    HiveCardDetail getCard(String serverUrl, String golemId, String accessToken, String cardId);

    List<HiveCardSummary> searchCards(String serverUrl, String golemId, String accessToken,
            HiveCardSearchRequest request);

    HiveCardDetail createCard(String serverUrl, String golemId, String accessToken, HiveCreateCardRequest request);

    HiveThreadMessage postThreadMessage(String serverUrl, String golemId, String accessToken, String threadId,
            String body);

    HiveCardDetail requestReview(String serverUrl, String golemId, String accessToken, String cardId,
            HiveRequestReviewRequest request);

    boolean isAuthorizationFailure(RuntimeException exception);
}
