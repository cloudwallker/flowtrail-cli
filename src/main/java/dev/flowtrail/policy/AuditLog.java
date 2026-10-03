package dev.flowtrail.policy;

import dev.flowtrail.model.Json;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

public final class AuditLog {
  private final Path file;

  public AuditLog(Path root) throws Exception {
    Path dir = root.resolve(".flowtrail");
    if (Files.isSymbolicLink(dir)) throw new SecurityException("Unsafe audit directory");
    Files.createDirectories(dir);
    file = dir.resolve("audit.jsonl");
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new SecurityException("Unsafe audit file");
  }

  public synchronized void record(String session, String tool, String status, long elapsedMillis)
      throws Exception {
    if (Files.isSymbolicLink(file)) throw new SecurityException("Unsafe audit file");
    Files.writeString(
        file,
        Json.encode(
                Map.of(
                    "time",
                    Instant.now().toString(),
                    "session",
                    session,
                    "tool",
                    tool,
                    "status",
                    status,
                    "elapsedMillis",
                    elapsedMillis))
            + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
