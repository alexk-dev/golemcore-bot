/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.domain.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;

@Slf4j
final class RuntimeConfigInviteCodeSupport {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_CODE_LENGTH = 20;

    private RuntimeConfigInviteCodeSupport() {
    }

    static RuntimeConfig.InviteCode generateInviteCode(RuntimeConfig cfg) {
        RuntimeConfig.InviteCode inviteCode = RuntimeConfig.InviteCode.builder().code(generateCode()).used(false)
                .createdAt(Instant.now()).build();

        List<RuntimeConfig.InviteCode> inviteCodes = ensureMutableInviteCodes(cfg.getTelegram());
        inviteCodes.add(inviteCode);

        log.info("[RuntimeConfig] Generated invite code: {}", inviteCode.getCode());
        return inviteCode;
    }

    static boolean revokeInviteCode(RuntimeConfig cfg, String code) {
        List<RuntimeConfig.InviteCode> codes = ensureMutableInviteCodes(cfg.getTelegram());
        boolean removed = codes.removeIf(ic -> ic.getCode().equals(code));
        if (removed) {
            log.info("[RuntimeConfig] Revoked invite code: {}", code);
        }
        return removed;
    }

    static boolean redeemInviteCode(RuntimeConfig cfg, String code, String userId) {
        List<RuntimeConfig.InviteCode> codes = ensureMutableInviteCodes(cfg.getTelegram());

        List<String> allowed = ensureMutableAllowedUsers(cfg.getTelegram());
        if (!allowed.isEmpty() && !allowed.contains(userId)) {
            log.warn("[RuntimeConfig] Invite redemption denied for user {}: invited user already registered", userId);
            return false;
        }

        for (RuntimeConfig.InviteCode ic : codes) {
            if (ic.getCode().equals(code)) {
                if (ic.isUsed()) {
                    return false;
                }
                ic.setUsed(true);
                if (!allowed.contains(userId)) {
                    allowed.add(userId);
                }
                log.info("[RuntimeConfig] Redeemed invite code {} for user {}", code, userId);
                return true;
            }
        }
        return false;
    }

    static boolean removeTelegramAllowedUser(RuntimeConfig cfg, String userId) {
        RuntimeConfig.TelegramConfig telegramConfig = cfg.getTelegram();
        List<String> allowedUsers = ensureMutableAllowedUsers(telegramConfig);
        if (allowedUsers.isEmpty()) {
            return false;
        }
        boolean removed = allowedUsers.removeIf(existingUserId -> existingUserId.equals(userId));
        if (removed) {
            int revokedCodes = revokeActiveInviteCodes(telegramConfig);
            log.info("[RuntimeConfig] Removed telegram allowed user: {} (revoked {} active invite codes)", userId,
                    revokedCodes);
        }
        return removed;
    }

    static List<String> ensureMutableAllowedUsers(RuntimeConfig.TelegramConfig telegramConfig) {
        List<String> allowedUsers = telegramConfig.getAllowedUsers();
        if (allowedUsers == null) {
            List<String> mutableAllowedUsers = new ArrayList<>();
            telegramConfig.setAllowedUsers(mutableAllowedUsers);
            return mutableAllowedUsers;
        }
        if (!(allowedUsers instanceof ArrayList<?>)) {
            List<String> mutableAllowedUsers = new ArrayList<>(allowedUsers);
            telegramConfig.setAllowedUsers(mutableAllowedUsers);
            return mutableAllowedUsers;
        }
        return allowedUsers;
    }

    static List<RuntimeConfig.InviteCode> ensureMutableInviteCodes(RuntimeConfig.TelegramConfig telegramConfig) {
        List<RuntimeConfig.InviteCode> inviteCodes = telegramConfig.getInviteCodes();
        if (inviteCodes == null) {
            List<RuntimeConfig.InviteCode> mutableInviteCodes = new ArrayList<>();
            telegramConfig.setInviteCodes(mutableInviteCodes);
            return mutableInviteCodes;
        }
        if (!(inviteCodes instanceof ArrayList<?>)) {
            List<RuntimeConfig.InviteCode> mutableInviteCodes = new ArrayList<>(inviteCodes);
            telegramConfig.setInviteCodes(mutableInviteCodes);
            return mutableInviteCodes;
        }
        return inviteCodes;
    }

    private static String generateCode() {
        StringBuilder code = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            code.append(INVITE_CHARS.charAt(SECURE_RANDOM.nextInt(INVITE_CHARS.length())));
        }
        return code.toString();
    }

    private static int revokeActiveInviteCodes(RuntimeConfig.TelegramConfig telegramConfig) {
        List<RuntimeConfig.InviteCode> inviteCodes = ensureMutableInviteCodes(telegramConfig);
        if (inviteCodes.isEmpty()) {
            return 0;
        }

        List<RuntimeConfig.InviteCode> retainedInviteCodes = new ArrayList<>(inviteCodes.size());
        int revokedCount = 0;
        for (RuntimeConfig.InviteCode inviteCode : inviteCodes) {
            if (inviteCode != null && !inviteCode.isUsed()) {
                revokedCount++;
                continue;
            }
            retainedInviteCodes.add(inviteCode);
        }

        if (revokedCount > 0) {
            telegramConfig.setInviteCodes(retainedInviteCodes);
        }
        return revokedCount;
    }
}
