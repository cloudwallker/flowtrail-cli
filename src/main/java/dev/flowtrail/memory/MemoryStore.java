package dev.flowtrail.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Explicitly saved facts are context data, never tool authorization. */
public final class MemoryStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ProjectDatabase db;

  public record Memory(String id, String content, String source, String createdAt) {}

  public record Session(String id, String messagesJson, String summary, String updatedAt) {}

  public MemoryStore(Path root) {
    db = new ProjectDatabase(root);
    try (var connection = db.open();
        var s = connection.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS memories(project TEXT NOT NULL,id TEXT NOT NULL,content TEXT"
              + " NOT NULL,source TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(project,id))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS sessions(project TEXT NOT NULL,id TEXT NOT NULL,messages TEXT"
              + " NOT NULL,summary TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY"
              + " KEY(project,id))");
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public Memory save(String content, String source) {
    requireText(content, "Memory content", 32_000);
    requireText(source, "Memory source", 2_000);
    var memory =
        new Memory(UUID.randomUUID().toString(), content, source, Instant.now().toString());
    try (var c = db.open();
        var s = c.prepareStatement("INSERT INTO memories VALUES(?,?,?,?,?)")) {
      s.setString(1, db.project());
      s.setString(2, memory.id());
      s.setString(3, content);
      s.setString(4, source);
      s.setString(5, memory.createdAt());
      s.executeUpdate();
      return memory;
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public List<Memory> list() {
    try (var c = db.open();
        var s =
            c.prepareStatement(
                "SELECT id,content,source,created_at FROM memories WHERE project=? ORDER BY"
                    + " created_at,id")) {
      s.setString(1, db.project());
      try (var rows = s.executeQuery()) {
        var result = new ArrayList<Memory>();
        while (rows.next())
          result.add(
              new Memory(
                  rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
        return List.copyOf(result);
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public List<Memory> recall(String query, int limit) {
    requireText(query, "Query", 8_000);
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be 1..100");
    String[] words = query.toLowerCase(Locale.ROOT).split("\\s+");
    return list().stream()
        .filter(m -> score(m, words) > 0)
        .sorted(Comparator.<Memory>comparingInt(m -> score(m, words)).reversed())
        .limit(limit)
        .toList();
  }

  private int score(Memory memory, String[] words) {
    String text = (memory.content() + " " + memory.source()).toLowerCase(Locale.ROOT);
    int score = 0;
    for (String word : words) if (text.contains(word)) score++;
    return score;
  }

  public boolean delete(String id) {
    try (var c = db.open();
        var s = c.prepareStatement("DELETE FROM memories WHERE project=? AND id=?")) {
      s.setString(1, db.project());
      s.setString(2, id);
      return s.executeUpdate() > 0;
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public void saveSession(String id, String messagesJson, String summary) {
    requireText(id, "Session id", 200);
    requireText(messagesJson, "Messages", 2_000_000);
    if (summary == null || summary.length() > 32_000)
      throw new IllegalArgumentException("Invalid summary");
    try {
      if (!JSON.readTree(messagesJson).isArray())
        throw new IllegalArgumentException("Session messages must be a JSON array");
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Invalid session JSON", e);
    }
    try (var c = db.open();
        var s =
            c.prepareStatement(
                "INSERT INTO sessions VALUES(?,?,?,?,?) ON CONFLICT(project,id) DO UPDATE SET"
                    + " messages=excluded.messages,summary=excluded.summary,updated_at=excluded.updated_at")) {
      s.setString(1, db.project());
      s.setString(2, id);
      s.setString(3, messagesJson);
      s.setString(4, summary);
      s.setString(5, Instant.now().toString());
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public Optional<Session> loadSession(String id) {
    try (var c = db.open();
        var s =
            c.prepareStatement(
                "SELECT id,messages,summary,updated_at FROM sessions WHERE project=? AND id=?")) {
      s.setString(1, db.project());
      s.setString(2, id);
      try (var rows = s.executeQuery()) {
        return rows.next()
            ? Optional.of(
                new Session(
                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private static void requireText(String value, String label, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException(label + " is empty or too large");
  }

  private static IllegalStateException failure(SQLException e) {
    return new IllegalStateException("Project memory storage failed", e);
  }
}
