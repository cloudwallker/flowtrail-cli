package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.memory.ProjectDatabase;
import dev.flowtrail.rag.CodeIndex;
import dev.flowtrail.rag.EmbeddingProvider;
import dev.flowtrail.rag.Embeddings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeIndexSecurityTest {
  private static final String PRIVATE_MARKER = "SYNTHETIC_PRIVATE_FIXTURE_9347";
  private static final List<String> PROTECTED_PATHS =
      List.of(
          ".codex/auth.json",
          ".aws/config.json",
          ".ssh/settings.json",
          ".agents/config.json",
          ".env/config.json",
          ".env.local/config.json",
          "nested/.CoDeX/config.json",
          "nested/certificate.key/config.json",
          "nested/certificate.pem/config.json");
  @TempDir Path root;

  @Test
  void protectedPathsNeverEnterIndexOrEmbeddingRequests() throws Exception {
    createFixtures();
    var inputs = new ArrayList<String>();
    EmbeddingProvider embedding =
        new EmbeddingProvider() {
          final EmbeddingProvider delegate = Embeddings.mock();

          public String modelId() {
            return delegate.modelId();
          }

          public int dimensions() {
            return delegate.dimensions();
          }

          public double[] embed(String text) throws Exception {
            inputs.add(text);
            return delegate.embed(text);
          }
        };
    var index = new CodeIndex(root, embedding);
    var report = index.update();
    assertEquals(1, report.scannedFiles(), "Only the public fixture may be scanned");
    assertTrue(
        inputs.stream().noneMatch(value -> value.contains(PRIVATE_MARKER)),
        "Protected content must not reach an embedding provider");
    assertTrue(index.search(PRIVATE_MARKER, 100, CodeIndex.SearchMode.KEYWORD).hits().isEmpty());
    assertEquals(0, cachedPrivateChunks());
  }

  @Test
  void everySearchModeRejectsAndPurgesProtectedRowsFromAnExistingIndex() throws Exception {
    createFixtures();
    var index = new CodeIndex(root, Embeddings.mock());
    for (var mode : CodeIndex.SearchMode.values()) {
      for (String path : PROTECTED_PATHS) insertLegacyChunk(path);
      var result = index.search(PRIVATE_MARKER, 100, mode);
      assertTrue(
          result.hits().isEmpty(),
          "Legacy protected content must not reach search results in " + mode);
      assertEquals(0, cachedPrivateChunks(), "Protected cached rows must be removed");
    }
  }

  @Test
  void updatePurgesProtectedRowsAlreadyCachedByAnOlderVersion() throws Exception {
    createFixtures();
    var index = new CodeIndex(root, Embeddings.mock());
    for (String path : PROTECTED_PATHS) insertLegacyChunk(path);
    index.update();
    assertEquals(0, cachedPrivateChunks());
  }

  @Test
  void queryHonorsNewExclusionsForAlreadyIndexedFiles() throws Exception {
    Files.createDirectories(root.resolve("internal"));
    Files.writeString(root.resolve("internal/config.json"), PRIVATE_MARKER);
    new CodeIndex(root, Embeddings.mock()).update();
    var restricted = new CodeIndex(root, Embeddings.mock(), List.of("internal"));
    assertTrue(restricted.search(PRIVATE_MARKER, 5).hits().isEmpty());
    assertEquals(0, cachedPrivateChunks());
  }

  private void createFixtures() throws Exception {
    Files.writeString(root.resolve("Public.java"), "class Public { void publicMethod() {} }");
    for (String relative : PROTECTED_PATHS) {
      Path file = root.resolve(relative);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "{\"synthetic\":\"" + PRIVATE_MARKER + "\"}");
    }
  }

  private void insertLegacyChunk(String path) throws Exception {
    var db = new ProjectDatabase(root);
    try (var connection = db.open();
        var file = connection.prepareStatement("INSERT INTO code_files VALUES(?,?,?,0)");
        var chunk =
            connection.prepareStatement("INSERT INTO code_chunks VALUES(?,?,?,?,?,?,?,?,?)")) {
      file.setString(1, db.project());
      file.setString(2, path);
      file.setString(3, "legacy-fixture");
      file.executeUpdate();
      chunk.setString(1, db.project());
      chunk.setString(2, path);
      chunk.setInt(3, 0);
      chunk.setString(4, "synthetic");
      chunk.setString(5, "text");
      chunk.setInt(6, 1);
      chunk.setInt(7, 1);
      chunk.setString(8, PRIVATE_MARKER);
      chunk.setString(
          9, new ObjectMapper().writeValueAsString(Embeddings.mock().embed(PRIVATE_MARKER)));
      chunk.executeUpdate();
    }
  }

  private int cachedPrivateChunks() throws Exception {
    var db = new ProjectDatabase(root);
    try (var connection = db.open();
        var query =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM code_chunks WHERE project=? AND content LIKE ?")) {
      query.setString(1, db.project());
      query.setString(2, "%" + PRIVATE_MARKER + "%");
      try (var rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
