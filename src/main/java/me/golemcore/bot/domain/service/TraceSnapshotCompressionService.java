package me.golemcore.bot.domain.service;

import com.github.luben.zstd.Zstd;
import org.springframework.stereotype.Service;

@Service
public class TraceSnapshotCompressionService {

    private static final String ZSTD = "zstd";

    public byte[] compress(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new byte[0];
        }
        return Zstd.compress(payload);
    }

    public byte[] decompress(String encoding, byte[] compressedPayload) {
        if (compressedPayload == null || compressedPayload.length == 0) {
            return new byte[0];
        }
        if (!ZSTD.equalsIgnoreCase(encoding)) {
            throw new IllegalArgumentException("Unsupported trace snapshot encoding: " + encoding);
        }
        long expectedSize = Zstd.decompressedSize(compressedPayload);
        if (expectedSize <= 0L || expectedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unable to determine decompressed trace snapshot size");
        }
        return Zstd.decompress(compressedPayload, (int) expectedSize);
    }
}
