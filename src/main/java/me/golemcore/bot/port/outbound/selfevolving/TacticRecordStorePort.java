package me.golemcore.bot.port.outbound.selfevolving;

import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;

public interface TacticRecordStorePort {

    List<TacticRecord> loadAll();

    void save(TacticRecord record);

    void delete(String tacticId);
}
