package me.golemcore.bot.adapter.outbound.gonka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GonkaRequestSignerTest {

    private static final String PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void shouldSignPayloadAndDeriveRequesterAddressWhenAddressMissing() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
        GonkaRequestSigner signer = new GonkaRequestSigner(clock);

        GonkaRequestSigner.SignedRequest signed = signer.sign(
                "{\"model\":\"qwen\"}",
                PRIVATE_KEY,
                null,
                "gonka1provideraddress");

        assertEquals("1700000000000000000", signed.timestamp());
        assertEquals("gonka1w508d6qejxtdg4y5r3zarvary0c5xw7k2gsyg6", signed.requesterAddress());
        assertNotNull(signed.authorization());
        assertEquals(64, Base64.getDecoder().decode(signed.authorization()).length);
        assertFalse(signed.authorization().startsWith("Bearer "));
    }

    @Test
    void shouldIncreaseTimestampWhenClockDoesNotMove() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
        GonkaRequestSigner signer = new GonkaRequestSigner(clock);

        GonkaRequestSigner.SignedRequest first = signer.sign("{}", PRIVATE_KEY, "gonka1requester",
                "gonka1provideraddress");
        GonkaRequestSigner.SignedRequest second = signer.sign("{}", PRIVATE_KEY, "gonka1requester",
                "gonka1provideraddress");

        assertEquals("1700000000000000000", first.timestamp());
        assertEquals("1700000000000000001", second.timestamp());
    }

    @Test
    void shouldUseExplicitRequesterAddress() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
        GonkaRequestSigner signer = new GonkaRequestSigner(clock);

        GonkaRequestSigner.SignedRequest signed = signer.sign(
                "{}",
                PRIVATE_KEY,
                "gonka1requester",
                "gonka1provideraddress");

        assertEquals("gonka1requester", signed.requesterAddress());
        assertTrue(signed.authorization().length() > 0);
    }
}
