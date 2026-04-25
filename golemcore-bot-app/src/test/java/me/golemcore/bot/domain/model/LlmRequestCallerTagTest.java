package me.golemcore.bot.domain.model;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmRequestCallerTagTest {

    @Test
    void callerTagIsNullWhenNotSet() {
        LlmRequest request = LlmRequest.builder().model("m").build();
        assertNull(request.getCallerTag());
    }

    @Test
    void callerTagRoundTripsThroughBuilder() {
        LlmRequest request = LlmRequest.builder()
                .model("m")
                .callerTag("follow_through")
                .build();
        assertEquals("follow_through", request.getCallerTag());
    }
}
