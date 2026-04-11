package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("PMD.LooseCoupling")
class TacticUsageAttributionServiceTest {

    @Test
    void shouldDeduplicateBundleAndAppliedTacticIdsPerRun() {
        TacticUsageAttributionService service = new TacticUsageAttributionService();
        RunRecord run = RunRecord.builder()
                .artifactBundleId("bundle-1")
                .appliedTacticIds(List.of("tactic-b", "tactic-c", "unknown"))
                .build();

        LinkedHashSet<String> attributed = service.resolveAttributedTacticIds(
                run,
                Map.of("bundle-1", List.of("tactic-a", "tactic-b", "tactic-a")),
                Set.of("tactic-a", "tactic-b", "tactic-c"));

        assertEquals(List.of("tactic-a", "tactic-b", "tactic-c"), new ArrayList<>(attributed));
    }
}
