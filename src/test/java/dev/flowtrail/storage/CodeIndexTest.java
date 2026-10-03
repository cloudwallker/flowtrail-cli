package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.rag.CodeIndex;
import dev.flowtrail.rag.EmbeddingProvider;
import dev.flowtrail.rag.Embeddings;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeIndexTest {
  @TempDir Path root;

  @Test
  void semanticSourcesAndIncrementalAddModifyDeleteAreVisible() throws Exception {
    Path source = root.resolve("Orders.java");
    Files.writeString(source, "class Orders {\n  String refundOrder() { return \"refund\"; }\n}\n");
    Files.createDirectories(root.resolve(".git"));
    Files.writeString(root.resolve(".git/secret.java"), "class Secret {}");
    Files.writeString(root.resolve(".env"), "PRIVATE=example");
    var index = new CodeIndex(root, Embeddings.mock());
    assertEquals(1, index.update().updatedFiles());
    assertEquals(1, index.update().unchangedFiles());
    var first = index.search("refundOrder", 5);
    assertFalse(first.hits().isEmpty());
    assertTrue(
        first.hits().stream().allMatch(h -> h.path().equals("Orders.java") && h.startLine() > 0));
    assertTrue(first.hits().stream().anyMatch(h -> h.symbol().contains("refundOrder")));
    Files.writeString(source, "class Orders {\n  String cancelOrder() { return \"cancel\"; }\n}\n");
    assertEquals(1, index.update().updatedFiles());
    assertTrue(
        index.search("cancelOrder", 5).hits().stream()
            .noneMatch(h -> h.content().contains("refundOrder")));
    Files.delete(source);
    assertEquals(1, index.update().deletedFiles());
    assertTrue(index.search("cancelOrder", 5).hits().isEmpty());
  }

  @Test
  void failedEmbeddingKeepsOldVersionAndExplicitlyMarksStaleAndKeywordFallback() throws Exception {
    Files.writeString(
        root.resolve("Orders.java"), "class Orders { int refundOrder() { return 1; } }");
    var provider = new SwitchableProvider();
    var index = new CodeIndex(root, provider);
    index.update();
    Files.writeString(
        root.resolve("Orders.java"), "class Orders { int cancelOrder() { return 2; } }");
    provider.fail = true;
    assertEquals(1, index.update().failures().size());
    var result = index.search("refundOrder", 5);
    assertTrue(result.degraded());
    assertTrue(result.stale());
    assertFalse(result.hits().isEmpty());
    assertTrue(result.hits().getFirst().content().contains("refundOrder"));
    provider.fail = false;
    index.update();
    assertFalse(index.search("cancelOrder", 5).stale());
  }

  @Test
  void incompatibleModelRefusesSearchAndUpdateUntilExplicitRebuild() throws Exception {
    Files.writeString(root.resolve("A.java"), "class A {}");
    new CodeIndex(root, Embeddings.mock()).update();
    var other =
        new CodeIndex(
            root,
            new EmbeddingProvider() {
              public String modelId() {
                return "other";
              }

              public int dimensions() {
                return 2;
              }

              public double[] embed(String text) {
                return new double[] {1, 0};
              }
            });
    assertThrows(IllegalStateException.class, other::update);
    assertThrows(IllegalStateException.class, () -> other.search("A", 5));
  }

  @Test
  void editsAfterIndexAreReportedAsStaleEvenBeforeNextUpdate() throws Exception {
    Path file = root.resolve("Orders.java");
    Files.writeString(file, "class Orders { void refundOrder() {} }");
    var index = new CodeIndex(root, Embeddings.mock());
    index.update();
    Files.writeString(file, "class Orders { void cancelOrder() {} }");
    assertTrue(index.search("refundOrder", 5).stale());
  }

  @Test
  void excludedPathsAreNotIndexedAndSameFileCannotFillAllResults() throws Exception {
    Files.createDirectories(root.resolve("private"));
    Files.writeString(root.resolve("private/Hidden.java"), "class Hidden { void query() {} }");
    Files.writeString(
        root.resolve("Many.java"),
        "class Many {\n"
            + " void queryOne() {}\n"
            + " void queryTwo() {}\n"
            + " void queryThree() {}\n"
            + " void queryFour() {}\n"
            + "}");
    var index = new CodeIndex(root, Embeddings.mock(), java.util.List.of("private"));
    assertEquals(1, index.update().scannedFiles());
    assertEquals(3, index.search("query", 20, CodeIndex.SearchMode.KEYWORD).hits().size());
    Files.writeString(root.resolve("Many.java"), "class Many { broken code");
    assertEquals(1, index.update().failures().size());
    assertTrue(index.search("query", 20).stale());
    assertTrue(
        index.search("query", 20).hits().stream().anyMatch(h -> h.content().contains("query")));
  }

  private static final class SwitchableProvider implements EmbeddingProvider {
    boolean fail;
    final EmbeddingProvider delegate = Embeddings.mock();

    public String modelId() {
      return delegate.modelId();
    }

    public int dimensions() {
      return delegate.dimensions();
    }

    public double[] embed(String text) throws Exception {
      if (fail) throw new java.io.IOException("Embedding unavailable");
      return delegate.embed(text);
    }
  }
}
