package me.golemcore.bot.adapter.outbound.selfevolving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.selfevolving.TacticRecordStorePort;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JsonTacticRecordStoreAdapter implements TacticRecordStorePort {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String TACTICS_PREFIX = "tactics/";
    private static final String TACTICS_LIST_PREFIX = "tactics";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public JsonTacticRecordStoreAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<TacticRecord> loadAll() {
        List<String> paths;
        try {
            paths = storagePort.listObjects(SELF_EVOLVING_DIR, TACTICS_LIST_PREFIX).join();
        } catch (RuntimeException exception) {
            log.debug("[TacticSearch] Failed to list tactics: {}", exception.getMessage());
            return new ArrayList<>();
        }

        List<TacticRecord> records = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.isBlank() || !path.startsWith(TACTICS_PREFIX) || !path.endsWith(".json")) {
                continue;
            }
            try {
                String json = storagePort.getText(SELF_EVOLVING_DIR, path).join();
                if (json == null || json.isBlank()) {
                    continue;
                }
                TacticRecord record = objectMapper.readValue(json, TacticRecord.class);
                if (record != null) {
                    records.add(record);
                }
            } catch (IOException | RuntimeException exception) {
                log.debug("[TacticSearch] Failed to load tactic '{}': {}", path, exception.getMessage());
            }
        }
        records.sort(Comparator.comparing(TacticRecord::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return records;
    }

    @Override
    public void save(TacticRecord record) {
        try {
            String path = TACTICS_PREFIX + record.getTacticId() + ".json";
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, path, objectMapper.writeValueAsString(record), true).join();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize tactic record", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to persist tactic record", exception);
        }
    }

    @Override
    public void delete(String tacticId) {
        try {
            storagePort.deleteObject(SELF_EVOLVING_DIR, TACTICS_PREFIX + tacticId + ".json").join();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to delete tactic record", exception);
        }
    }
}
