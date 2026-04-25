package me.golemcore.bot.port.outbound;

import java.util.List;

/**
 * Outbound port for issuing dashboard session tokens for an already-validated
 * federated identity supplied by another module.
 */
public interface DashboardFederatedAuthPort {

    DashboardSessionTokens issueDashboardTokensForPrincipal(DashboardFederatedPrincipal principal);

    record DashboardFederatedPrincipal(String subject, String displayName, List<String> roles) {
    }

    record DashboardSessionTokens(String accessToken, String refreshToken) {
    }
}
