package me.golemcore.bot.domain.service;

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared telemetry-safe formatting helpers.
 */
public final class TelemetrySupport {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int SHORT_HASH_LEN = 12;

    private TelemetrySupport() {
    }

    public static String shortHash(String value) {
        if (StringValueSupport.isBlank(value)) {
            return "na";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int v = b & 0xFF;
                builder.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            }
            if (builder.length() <= SHORT_HASH_LEN) {
                return builder.toString();
            }
            return builder.substring(0, SHORT_HASH_LEN);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
