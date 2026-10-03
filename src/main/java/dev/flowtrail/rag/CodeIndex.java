package dev.flowtrail.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.memory.ProjectDatabase;
import dev.flowtrail.policy.ExecutionPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small repository index: SQLite persistence and in-memory cosine/keyword fusion. */
public final class CodeIndex {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> IGNORED =
      Set.of(
          ".git",
          ".flowtrail",
          ".cache",
          "target",
          "build",
          "dist",
          "out",
          "node_modules",
          ".idea",
          ".venv",
          "licenses");
  private static final Set<String> EXTENSIONS =
      Set.of(
          "java",
          "md",
          "txt",
          "xml",
          "yaml",
          "yml",
          "json",
          "properties",
          "py",
          "js",
          "ts",
          "tsx",
          "jsx",
          "kt",
          "go",
          "rs",
          "c",
          "cpp",
          "h",
          "sh",
          "sql");
  private final ProjectDatabase db;
  private final ExecutionPolicy readPolicy;
  private final EmbeddingProvider embedding;
  private final List<String> excludedPaths;

  public enum SearchMode {
    HYBRID,
    KEYWORD,
    VECTOR
  }

  public record Failure(String path, String reason) {}

  public record IndexReport(
      int scannedFiles,
      int updatedFiles,
      int unchangedFiles,
      int deletedFiles,
      int chunks,
      List<Failure> failures,
      long elapsedNanos,
      String embeddingModel,
      boolean mock) {}

  public record Timings(
      long embeddingNanos, long vectorNanos, long keywordAndFusionNanos, long totalNanos) {}

  public record Hit(
      String path,
      String symbol,
      String kind,
      int startLine,
      int endLine,
      String content,
      double score,
      double vectorScore,
      double keywordScore,
      boolean stale) {}

  public record SearchReport(
      List<Hit> hits,
      boolean degraded,
      String degradationReason,
      boolean stale,
      Timings timings,
      String embeddingModel,
      boolean mock,
      SearchMode mode) {}

  private record FileState(String hash, boolean stale) {}

  private record Stored(CodeChunker.Chunk chunk, double[] vector, boolean stale) {}

  private record Candidate(CodeChunker.Chunk chunk, double[] vector) {}

  public CodeIndex(Path root, EmbeddingProvider embedding) {
    this(root, embedding, List.of());
  }

  public CodeIndex(Path root, EmbeddingProvider embedding, List<String> excludedPaths) {
    this.db = new ProjectDatabase(root);
    try {
      this.readPolicy = new ExecutionPolicy(db.root(), false, false, Set.of());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot initialize index path policy", e);
    }
    this.embedding = embedding;
    this.excludedPaths =
        excludedPaths.stream().map(p -> p.replace('\\', '/').replaceAll("/$", "")).toList();
    if (embedding.dimensions() < 1)
      throw new IllegalArgumentException("Invalid embedding dimensions");
    try (var c = db.open();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS code_index_meta(project TEXT PRIMARY KEY,model TEXT NOT"
              + " NULL,dimensions INTEGER NOT NULL,chunk_version TEXT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS code_files(project TEXT NOT NULL,path TEXT NOT NULL,hash TEXT"
              + " NOT NULL,stale INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(project,path))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS code_chunks(project TEXT NOT NULL,path TEXT NOT NULL,ordinal"
              + " INTEGER NOT NULL,symbol TEXT NOT NULL,kind TEXT NOT NULL,start_line INTEGER NOT"
              + " NULL,end_line INTEGER NOT NULL,content TEXT NOT NULL,vector TEXT NOT NULL,PRIMARY"
              + " KEY(project,path,ordinal),FOREIGN KEY(project,path) REFERENCES"
              + " code_files(project,path) ON DELETE CASCADE)");
    } catch (SQLException e) {
      throw storageFailure(e);
    }
  }

  /** Explicitly discard this project's index before changing model/dimension/chunking version. */
  public void reset() {
    try (var c = db.open()) {
      c.setAutoCommit(false);
      try (var files = c.prepareStatement("DELETE FROM code_files WHERE project=?");
          var meta = c.prepareStatement("DELETE FROM code_index_meta WHERE project=?")) {
        files.setString(1, db.project());
        files.executeUpdate();
        meta.setString(1, db.project());
        meta.executeUpdate();
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw storageFailure(e);
    }
  }

  public IndexReport update() {
    long started = System.nanoTime();
    verifyModel(true);
    Map<String, FileState> previous = files();
    var seen = new HashSet<String>();
    var failures = new ArrayList<Failure>();
    int updated = 0, unchanged = 0, deleted = 0;
    var paths = new ArrayList<Path>();
    try {
      Files.walkFileTree(
          db.root(),
          new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
              return !dir.equals(db.root()) && ignoredPath(dir)
                  ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                  : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (attrs.isRegularFile() && eligible(path)) paths.add(path);
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
      paths.sort(Comparator.naturalOrder());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot scan project", e);
    }
    for (Path path : paths) {
      // Recheck immediately before reading; the filesystem may have changed since traversal.
      if (!eligible(path)) continue;
      String relative = relative(path);
      seen.add(relative);
      try {
        if (Files.size(path) > 1_000_000)
          throw new IllegalArgumentException("File exceeds 1 MB index limit");
        byte[] bytes = Files.readAllBytes(path);
        String hash = hash(bytes);
        var old = previous.get(relative);
        if (old != null && old.hash().equals(hash) && !old.stale()) {
          unchanged++;
          continue;
        }
        var chunks = new CodeChunker().split(relative, new String(bytes, StandardCharsets.UTF_8));
        var candidates = new ArrayList<Candidate>();
        for (var chunk : chunks) {
          double[] vector = embedding.embed(chunk.symbol() + "\n" + chunk.content());
          Embeddings.validate(vector, embedding.dimensions());
          candidates.add(new Candidate(chunk, vector));
        }
        if (!hash(Files.readAllBytes(path)).equals(hash))
          throw new IllegalStateException("File changed during indexing");
        replace(relative, hash, candidates);
        updated++;
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Indexing interrupted", e);
        }
        markStale(relative);
        failures.add(
            new Failure(
                relative,
                e instanceof IllegalArgumentException
                    ? e.getMessage()
                    : "Index generation failed; previous committed version retained"));
      }
    }
    for (String path : previous.keySet())
      if (!seen.contains(path)) {
        delete(path);
        deleted++;
      }
    return new IndexReport(
        seen.size(),
        updated,
        unchanged,
        deleted,
        chunkCount(),
        List.copyOf(failures),
        System.nanoTime() - started,
        embedding.modelId(),
        isMock());
  }

  public SearchReport search(String query, int limit) {
    return search(query, limit, SearchMode.HYBRID);
  }

  public SearchReport search(String query, int limit, SearchMode mode) {
    if (query == null || query.isBlank() || query.length() > 8_000 || limit < 1 || limit > 100)
      throw new IllegalArgumentException(
          "Nonempty query <=8000 characters and limit 1..100 required");
    long started = System.nanoTime();
    verifyModel(false);
    // An older version may have cached paths the current execution policy forbids.
    // Remove those rows before deserializing chunks or producing any search results.
    purgeDisallowedFiles();
    double[] queryVector = null;
    boolean degraded = false;
    String reason = "";
    long embeddingStarted = System.nanoTime();
    if (mode != SearchMode.KEYWORD) {
      try {
        queryVector = embedding.embed(query);
        Embeddings.validate(queryVector, embedding.dimensions());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Search interrupted", e);
        }
        degraded = true;
        reason = "Query embedding unavailable; keyword-only fallback";
      }
    }
    long embeddingNanos = System.nanoTime() - embeddingStarted;
    long vectorStarted = System.nanoTime();
    var stored = readChunks();
    var vectorScores = new ArrayList<Double>();
    for (var row : stored)
      vectorScores.add(queryVector == null ? 0 : cosine(queryVector, row.vector()));
    long vectorNanos = System.nanoTime() - vectorStarted;
    long fusionStarted = System.nanoTime();
    List<String> terms = KeywordTokenizer.terms(query);
    var hits = new ArrayList<Hit>();
    for (int i = 0; i < stored.size(); i++) {
      var row = stored.get(i);
      var chunk = row.chunk();
      String text = (chunk.symbol() + " " + chunk.content()).toLowerCase(Locale.ROOT);
      long matches = terms.stream().filter(text::contains).count();
      double keyword = terms.isEmpty() ? 0 : (double) matches / terms.size();
      double vector = Math.max(0, vectorScores.get(i));
      double score =
          (queryVector == null || mode == SearchMode.KEYWORD)
              ? keyword
              : mode == SearchMode.VECTOR ? vector : 0.6 * vector + 0.4 * keyword;
      if (score <= 0) continue;
      score *= chunk.kind().equals("method") ? 1.05 : 1;
      hits.add(
          new Hit(
              chunk.path(),
              chunk.symbol(),
              chunk.kind(),
              chunk.startLine(),
              chunk.endLine(),
              chunk.content(),
              score,
              vector,
              keyword,
              row.stale()));
    }
    hits.sort(
        Comparator.comparingDouble(Hit::score)
            .reversed()
            .thenComparing(Hit::path)
            .thenComparingInt(Hit::startLine));
    var counts = new HashMap<String, Integer>();
    var selected = new ArrayList<Hit>();
    for (var hit : hits) {
      if (counts.getOrDefault(hit.path(), 0) >= 3) continue;
      selected.add(hit);
      counts.merge(hit.path(), 1, Integer::sum);
      if (selected.size() >= limit) break;
    }
    long fusionNanos = System.nanoTime() - fusionStarted;
    return new SearchReport(
        List.copyOf(selected),
        degraded,
        reason,
        stored.stream().anyMatch(Stored::stale),
        new Timings(embeddingNanos, vectorNanos, fusionNanos, System.nanoTime() - started),
        embedding.modelId(),
        isMock(),
        mode);
  }

  private boolean isMock() {
    return embedding.modelId().startsWith("mock-");
  }

  private boolean ignoredPath(Path path) {
    if (!permittedByExecutionPolicy(path)) return true;
    String rel = relative(path);
    for (Path part : db.root().relativize(path))
      if (IGNORED.contains(part.toString()) || part.toString().startsWith("target-")) return true;
    for (String exclude : excludedPaths)
      if (rel.equals(exclude) || rel.startsWith(exclude + "/")) return true;
    return false;
  }

  private boolean permittedByExecutionPolicy(Path path) {
    try {
      readPolicy.resolve(relative(path));
      return true;
    } catch (java.io.IOException | SecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private boolean eligibleCachedPath(String storedPath) {
    try {
      return eligible(db.root().resolve(storedPath).normalize());
    } catch (IllegalArgumentException | SecurityException e) {
      return false;
    }
  }

  private void purgeDisallowedFiles() {
    for (String path : files().keySet()) {
      if (!eligibleCachedPath(path)) delete(path);
    }
  }

  private boolean eligible(Path path) {
    if (Files.isSymbolicLink(path) || ignoredPath(path)) return false;
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.startsWith(".env")
        || name.contains("secret")
        || name.contains("credential")
        || name.startsWith("id_rsa")
        || name.startsWith("id_ed25519")) return false;
    int dot = name.lastIndexOf('.');
    if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1))) return false;
    try {
      return path.toRealPath().startsWith(db.root());
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private String relative(Path path) {
    return db.root().relativize(path).toString().replace('\\', '/');
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private void verifyModel(boolean initialize) {
    try (var c = db.open()) {
      verifyModel(c, initialize);
    } catch (SQLException e) {
      throw storageFailure(e);
    }
  }

  private void verifyModel(Connection c, boolean initialize) throws SQLException {
    if (initialize)
      try (var insert =
          c.prepareStatement("INSERT OR IGNORE INTO code_index_meta VALUES(?,?,?,?)")) {
        insert.setString(1, db.project());
        insert.setString(2, embedding.modelId());
        insert.setInt(3, embedding.dimensions());
        insert.setString(4, CodeChunker.VERSION);
        insert.executeUpdate();
      }
    try (var select =
        c.prepareStatement(
            "SELECT model,dimensions,chunk_version FROM code_index_meta WHERE project=?")) {
      select.setString(1, db.project());
      try (var rows = select.executeQuery()) {
        if (rows.next()
            && (!embedding.modelId().equals(rows.getString(1))
                || embedding.dimensions() != rows.getInt(2)
                || !CodeChunker.VERSION.equals(rows.getString(3))))
          throw new IllegalStateException(
              "Index model/dimension/chunk version mismatch; use index --rebuild explicitly");
      }
    }
  }

  private Map<String, FileState> files() {
    try (var c = db.open();
        var s = c.prepareStatement("SELECT path,hash,stale FROM code_files WHERE project=?")) {
      s.setString(1, db.project());
      var result = new HashMap<String, FileState>();
      try (var rows = s.executeQuery()) {
        while (rows.next())
          result.put(rows.getString(1), new FileState(rows.getString(2), rows.getInt(3) != 0));
      }
      return result;
    } catch (SQLException e) {
      throw storageFailure(e);
    }
  }

  private void replace(String path, String hash, List<Candidate> candidates) throws Exception {
    try (var c = db.open()) {
      c.setAutoCommit(false);
      try {
        verifyModel(c, true);
        try (var s = c.prepareStatement("DELETE FROM code_files WHERE project=? AND path=?")) {
          s.setString(1, db.project());
          s.setString(2, path);
          s.executeUpdate();
        }
        try (var s = c.prepareStatement("INSERT INTO code_files VALUES(?,?,?,0)")) {
          s.setString(1, db.project());
          s.setString(2, path);
          s.setString(3, hash);
          s.executeUpdate();
        }
        try (var s = c.prepareStatement("INSERT INTO code_chunks VALUES(?,?,?,?,?,?,?,?,?)")) {
          int ordinal = 0;
          for (var candidate : candidates) {
            var chunk = candidate.chunk();
            s.setString(1, db.project());
            s.setString(2, path);
            s.setInt(3, ordinal++);
            s.setString(4, chunk.symbol());
            s.setString(5, chunk.kind());
            s.setInt(6, chunk.startLine());
            s.setInt(7, chunk.endLine());
            s.setString(8, chunk.content());
            s.setString(9, JSON.writeValueAsString(candidate.vector()));
            s.addBatch();
          }
          s.executeBatch();
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  private void markStale(String path) {
    mutateFile("UPDATE code_files SET stale=1 WHERE project=? AND path=?", path);
  }

  private void delete(String path) {
    mutateFile("DELETE FROM code_files WHERE project=? AND path=?", path);
  }

  private void mutateFile(String sql, String path) {
    try (var c = db.open();
        var s = c.prepareStatement(sql)) {
      s.setString(1, db.project());
      s.setString(2, path);
      s.executeUpdate();
    } catch (SQLException e) {
      throw storageFailure(e);
    }
  }

  private int chunkCount() {
    try (var c = db.open();
        var s = c.prepareStatement("SELECT COUNT(*) FROM code_chunks WHERE project=?")) {
      s.setString(1, db.project());
      try (var rows = s.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    } catch (SQLException e) {
      throw storageFailure(e);
    }
  }

  private List<Stored> readChunks() {
    try (var c = db.open();
        var s =
            c.prepareStatement(
                "SELECT"
                    + " c.path,c.symbol,c.kind,c.start_line,c.end_line,c.content,c.vector,f.stale,f.hash"
                    + " FROM code_chunks c JOIN code_files f ON c.project=f.project AND"
                    + " c.path=f.path WHERE c.project=?")) {
      s.setString(1, db.project());
      var result = new ArrayList<Stored>();
      var staleFiles = new HashMap<String, Boolean>();
      var permittedFiles = new HashMap<String, Boolean>();
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          String path = rows.getString(1);
          // Defense at the read boundary also covers rows inserted after the cleanup pass.
          if (!permittedFiles.computeIfAbsent(path, this::eligibleCachedPath)) continue;
          var chunk =
              new CodeChunker.Chunk(
                  path,
                  rows.getString(2),
                  rows.getString(3),
                  rows.getInt(4),
                  rows.getInt(5),
                  rows.getString(6));
          double[] vector = JSON.readValue(rows.getString(7), double[].class);
          Embeddings.validate(vector, embedding.dimensions());
          String expectedHash = rows.getString(9);
          boolean stale =
              rows.getInt(8) != 0
                  || staleFiles.computeIfAbsent(chunk.path(), key -> isStale(key, expectedHash));
          result.add(new Stored(chunk, vector, stale));
        }
      }
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read project index", e);
    }
  }

  private boolean isStale(String relative, String expectedHash) {
    Path path = db.root().resolve(relative).normalize();
    try {
      if (!permittedByExecutionPolicy(path)
          || !path.startsWith(db.root())
          || Files.isSymbolicLink(path)
          || !path.toRealPath().startsWith(db.root())
          || Files.size(path) > 1_000_000) return true;
      return !hash(Files.readAllBytes(path)).equals(expectedHash);
    } catch (Exception e) {
      return true;
    }
  }

  private static double cosine(double[] left, double[] right) {
    double dot = 0;
    for (int i = 0; i < left.length; i++) dot += left[i] * right[i];
    return dot / (Embeddings.norm(left) * Embeddings.norm(right));
  }

  private static IllegalStateException storageFailure(SQLException e) {
    return new IllegalStateException("Project index storage failed", e);
  }
}
