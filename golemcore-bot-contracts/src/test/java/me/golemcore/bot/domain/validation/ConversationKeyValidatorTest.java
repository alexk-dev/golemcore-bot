package me.golemcore.bot.domain.validation;

import me.golemcore.bot.domain.model.AgentSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationKeyValidatorTest {

    @Test
    void shouldNormalizeAndValidateStrictConversationKeys() {
        assertTrue(ConversationKeyValidator.isStrictConversationKey(" abcDEF_12 "));
        assertFalse(ConversationKeyValidator.isStrictConversationKey("short"));
        assertFalse(ConversationKeyValidator.isStrictConversationKey("invalid.key"));

        assertEquals("abcDEF_12", ConversationKeyValidator.normalizeStrictOrThrow(" abcDEF_12 "));
        assertThrows(IllegalArgumentException.class,
                () -> ConversationKeyValidator.normalizeStrictOrThrow("invalid.key"));
    }

    @Test
    void shouldAllowExistingLegacyKeyOnlyForActivation() {
        assertEquals("abc", ConversationKeyValidator.normalizeForActivationOrThrow(" abc ", key -> true));

        assertThrows(IllegalArgumentException.class,
                () -> ConversationKeyValidator.normalizeForActivationOrThrow("abc", key -> false));
        assertThrows(IllegalArgumentException.class,
                () -> ConversationKeyValidator.normalizeForActivationOrThrow("abc", null));
    }

    @Test
    void shouldRejectBlankAndInvalidLegacyKeys() {
        assertFalse(ConversationKeyValidator.isLegacyCompatibleConversationKey(null));
        assertFalse(ConversationKeyValidator.isLegacyCompatibleConversationKey(" "));
        assertFalse(ConversationKeyValidator.isLegacyCompatibleConversationKey("invalid.key"));
        assertEquals("legacy_1", ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(" legacy_1 "));
        assertThrows(IllegalArgumentException.class,
                () -> ConversationKeyValidator.normalizeLegacyCompatibleOrThrow("invalid.key"));
    }

    @Test
    void shouldSortRecentSessionsByUpdatedThenCreatedKeepingMissingTimestampsLast() {
        AgentSession newestUpdated = session("newest-updated",
                Instant.parse("2026-04-16T10:00:00Z"),
                Instant.parse("2026-04-16T11:00:00Z"));
        AgentSession newestCreated = session("newest-created",
                Instant.parse("2026-04-16T10:30:00Z"),
                null);
        AgentSession olderUpdated = session("older-updated",
                Instant.parse("2026-04-16T08:00:00Z"),
                Instant.parse("2026-04-16T09:00:00Z"));
        AgentSession missingTimestamp = session("missing-timestamp", null, null);

        List<AgentSession> sorted = List.of(missingTimestamp, olderUpdated, newestCreated, newestUpdated).stream()
                .sorted(ConversationKeyValidator.byRecentActivity())
                .toList();

        assertEquals(List.of("newest-updated", "newest-created", "older-updated", "missing-timestamp"),
                sorted.stream().map(AgentSession::getId).toList());
    }

    private AgentSession session(String id, Instant createdAt, Instant updatedAt) {
        return AgentSession.builder()
                .id(id)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
