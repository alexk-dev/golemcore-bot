package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin account credentials persisted in preferences/admin.json. Single admin
 * account with optional MFA (TOTP).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCredentials {

    @Builder.Default
    private String username = "admin";

    private String passwordHash;

    @Builder.Default
    private boolean mfaEnabled = false;

    private String mfaSecret;

    private Instant mfaVerifiedAt;
}
