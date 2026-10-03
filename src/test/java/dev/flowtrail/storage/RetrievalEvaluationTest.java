package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.rag.Embeddings;
import dev.flowtrail.rag.RetrievalEvaluation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalEvaluationTest {
  @TempDir Path root;

  @Test
  void computesRankMetricsFromLabelsAndKeepsRawStageTimings() throws Exception {
    Files.writeString(root.resolve("Refund.java"), "class Refund { void refundOrder() {} }");
    var questions =
        List.of(
            new RetrievalEvaluation.Question("known", "refundOrder", List.of("Refund.java")),
            new RetrievalEvaluation.Question("missing", "zzzzzzzz", List.of("Missing.java")));
    var report = RetrievalEvaluation.evaluate(root, questions, Embeddings.mock(), 1);
    assertEquals(true, report.get("mock"));
    @SuppressWarnings("unchecked")
    var summaries = (List<RetrievalEvaluation.Aggregate>) report.get("summaries");
    var keyword =
        summaries.stream()
            .filter(a -> a.mode().equals("KEYWORD") && a.phase().equals("warm"))
            .findFirst()
            .orElseThrow();
    assertEquals(.5, keyword.recallAt5());
    assertEquals(.5, keyword.mrrAt5());
    assertEquals(12, ((List<?>) report.get("samples")).size());
    assertTrue(keyword.total().p50Millis() >= 0);
  }
}
