package dev.flowtrail.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Reproducible benchmark entry point: no target latency or quality numbers are prefilled. */
public final class RetrievalEvaluation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private RetrievalEvaluation() {}

  public record Question(String id, String query, List<String> relevantPaths) {}

  public record Sample(
      String id,
      String query,
      CodeIndex.SearchMode mode,
      String phase,
      int repeat,
      List<String> hitPaths,
      double recallAt5,
      double mrrAt5,
      boolean degraded,
      CodeIndex.Timings timings) {}

  public record Percentiles(double p50Millis, double p95Millis) {}

  public record Aggregate(
      String mode,
      String phase,
      int count,
      double recallAt5,
      double mrrAt5,
      long degradedCount,
      Percentiles embedding,
      Percentiles vector,
      Percentiles keywordAndFusion,
      Percentiles total) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3 || args.length > 6)
      throw new IllegalArgumentException(
          "Usage: RetrievalEvaluation PROJECT QUESTIONS_JSON REPORT_JSON [WARM_REPEATS=3]"
              + " [PROVIDER=mock] [MODEL]");
    Path project = Path.of(args[0]);
    List<Question> questions = JSON.readValue(Path.of(args[1]).toFile(), new TypeReference<>() {});
    int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 3;
    EmbeddingProvider provider =
        Embeddings.fromEnvironment(
            args.length > 4 ? args[4] : "mock",
            args.length > 5 ? args[5] : null,
            System.getenv("FLOWTRAIL_EMBEDDING_BASE_URL"));
    var report = evaluate(project, questions, provider, repeats);
    Path output = Path.of(args[2]).toAbsolutePath();
    Files.createDirectories(output.getParent());
    JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    System.out.println(
        JSON.writeValueAsString(
            Map.of(
                "report",
                output.getFileName().toString(),
                "model",
                provider.modelId(),
                "questions",
                questions.size())));
  }

  public static Map<String, Object> evaluate(
      Path project, List<Question> questions, EmbeddingProvider provider, int repeats) {
    if (questions.isEmpty() || repeats < 1 || repeats > 100)
      throw new IllegalArgumentException("Questions and 1..100 warm repeats required");
    for (var question : questions)
      if (question.relevantPaths() == null || question.relevantPaths().isEmpty())
        throw new IllegalArgumentException("Every question requires relevance labels");
    var index = new CodeIndex(project, provider);
    var build = index.update();
    if (!build.failures().isEmpty())
      throw new IllegalStateException("Index construction failed; evaluation stopped");
    var incremental = index.update();
    var samples = new ArrayList<Sample>();
    for (var mode : CodeIndex.SearchMode.values()) {
      for (int repeat = 0; repeat <= repeats; repeat++) {
        for (var question : questions) {
          var result = index.search(question.query(), 5, mode);
          List<String> paths = result.hits().stream().map(CodeIndex.Hit::path).toList();
          long found = question.relevantPaths().stream().filter(paths::contains).distinct().count();
          double recall = (double) found / question.relevantPaths().stream().distinct().count();
          double mrr = 0;
          for (int rank = 0; rank < paths.size(); rank++)
            if (question.relevantPaths().contains(paths.get(rank))) {
              mrr = 1.0 / (rank + 1);
              break;
            }
          samples.add(
              new Sample(
                  question.id(),
                  question.query(),
                  mode,
                  repeat == 0 ? "first-pass" : "warm",
                  repeat,
                  paths,
                  recall,
                  mrr,
                  result.degraded(),
                  result.timings()));
        }
      }
    }
    var aggregates = new ArrayList<Aggregate>();
    for (var mode : CodeIndex.SearchMode.values())
      for (String phase : List.of("first-pass", "warm")) {
        var group =
            samples.stream().filter(s -> s.mode() == mode && s.phase().equals(phase)).toList();
        aggregates.add(
            new Aggregate(
                mode.name(),
                phase,
                group.size(),
                group.stream().mapToDouble(Sample::recallAt5).average().orElse(0),
                group.stream().mapToDouble(Sample::mrrAt5).average().orElse(0),
                group.stream().filter(Sample::degraded).count(),
                percentile(group, s -> s.timings().embeddingNanos()),
                percentile(group, s -> s.timings().vectorNanos()),
                percentile(group, s -> s.timings().keywordAndFusionNanos()),
                percentile(group, s -> s.timings().totalNanos())));
      }
    var report = new LinkedHashMap<String, Object>();
    report.put("generatedAt", Instant.now().toString());
    report.put("java", System.getProperty("java.version"));
    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    report.put("model", provider.modelId());
    report.put("dimensions", provider.dimensions());
    report.put("chunkVersion", CodeChunker.VERSION);
    report.put("mock", provider.modelId().startsWith("mock-"));
    report.put(
        "timingScope",
        "In-process search. First-pass is not a JVM/OS cold-start benchmark. Vector stage includes"
            + " SQLite reads, freshness checks and cosine scoring.");
    report.put("build", build);
    report.put("unchangedUpdate", incremental);
    report.put("questionCount", questions.size());
    report.put("summaries", aggregates);
    report.put("samples", samples);
    return report;
  }

  private static Percentiles percentile(List<Sample> samples, ToLongFunction<Sample> value) {
    long[] sorted = samples.stream().mapToLong(value).sorted().toArray();
    return new Percentiles(
        sorted[Math.max(0, (int) Math.ceil(sorted.length * .5) - 1)] / 1_000_000.0,
        sorted[Math.max(0, (int) Math.ceil(sorted.length * .95) - 1)] / 1_000_000.0);
  }
}
