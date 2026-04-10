package me.golemcore.bot.port.outbound;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface WorkspaceFilePort {

    void createDirectories(Path path) throws IOException;

    String probeContentType(Path path) throws IOException;

    boolean exists(Path path);

    Path resolveRealPath(Path path) throws IOException;

    byte[] readAllBytes(Path path) throws IOException;

    String readString(Path path) throws IOException;

    void write(Path path, byte[] data) throws IOException;

    void writeString(Path path, String content) throws IOException;

    long size(Path path) throws IOException;

    boolean isRegularFile(Path path);

    boolean isDirectory(Path path);

    List<Path> list(Path path) throws IOException;

    List<Path> walk(Path path) throws IOException;

    void move(Path source, Path target) throws IOException;

    void delete(Path path) throws IOException;

    boolean deleteIfExists(Path path) throws IOException;

    boolean isSymbolicLink(Path path);

    String getLastModifiedTime(Path path) throws IOException;
}
