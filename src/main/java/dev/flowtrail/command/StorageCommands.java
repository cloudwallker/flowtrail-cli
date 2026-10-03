package dev.flowtrail.command;

import dev.flowtrail.FlowException;
import dev.flowtrail.memory.MemoryStore;
import dev.flowtrail.rag.CodeIndex;
import dev.flowtrail.rag.Embeddings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public final class StorageCommands {
  private StorageCommands() {}

  public abstract static class ProjectCommand extends Commands.OutputCommand {
    @Option(
        names = "--project",
        defaultValue = ".",
        description = "Project root; storage remains scoped to this canonical path.")
    Path project;
  }

  public abstract static class EmbeddingCommand extends ProjectCommand {
    @Option(
        names = "--embedding-provider",
        defaultValue = "mock",
        description = "mock (offline fixture), openai, or ollama.")
    String provider;

    @Option(names = "--embedding-model")
    String model;

    @Option(names = "--embedding-base-url")
    String baseUrl;

    CodeIndex index(List<String> excludes) {
      return new CodeIndex(project, Embeddings.fromEnvironment(provider, model, baseUrl), excludes);
    }
  }

  @Command(
      name = "memory",
      mixinStandardHelpOptions = true,
      description =
          "Explicit project fact save/list/recall/delete; facts never grant tool permissions.")
  public static final class Memory extends ProjectCommand {
    @Parameters(
        index = "0",
        defaultValue = "list",
        arity = "0..1",
        description = "save, list, recall, delete")
    String action;

    @Parameters(index = "1", arity = "0..1", description = "Content, query or memory ID")
    String value;

    @Option(names = "--source", defaultValue = "manual")
    String source;

    @Option(names = "--limit", defaultValue = "5")
    int limit;

    @Override
    public Integer call() {
      var store = new MemoryStore(project);
      Object result =
          switch (action) {
            case "save" -> store.save(required(), source);
            case "list" -> Map.of("memories", store.list());
            case "recall" -> Map.of("memories", store.recall(required(), limit));
            case "delete" -> Map.of("deleted", store.delete(required()));
            default ->
                throw new FlowException(2, "Memory action must be save, list, recall or delete");
          };
      reporter().success(result, result.toString());
      return 0;
    }

    private String required() {
      if (value == null || value.isBlank())
        throw new FlowException(2, "This memory action requires a value");
      return value;
    }
  }

  @Command(
      name = "index",
      mixinStandardHelpOptions = true,
      description =
          "Build/update a JavaParser code index; mock embeddings are explicit offline fixtures.")
  public static final class Index extends EmbeddingCommand {
    @Option(
        names = "--exclude",
        description = "Exclude a project-relative file or directory; repeatable.")
    List<String> excludes = List.of();

    @Option(
        names = "--rebuild",
        description = "Explicitly discard the old index to change embedding model/dimensions.")
    boolean rebuild;

    @Override
    public Integer call() {
      try {
        var index = index(excludes);
        if (rebuild) index.reset();
        var report = index.update();
        reporter()
            .success(
                report,
                "Indexed "
                    + report.chunks()
                    + " chunks; updated="
                    + report.updatedFiles()
                    + ", unchanged="
                    + report.unchangedFiles()
                    + ", deleted="
                    + report.deletedFiles()
                    + ", failures="
                    + report.failures().size()
                    + "; model="
                    + report.embeddingModel());
        return report.failures().isEmpty() ? 0 : 1;
      } catch (IllegalStateException | IllegalArgumentException e) {
        throw new FlowException(1, e.getMessage());
      }
    }
  }

  @Command(
      name = "search",
      mixinStandardHelpOptions = true,
      description =
          "Search indexed code with source locations, degradation flags and stage timings.")
  public static final class Search extends EmbeddingCommand {
    @Parameters(index = "0", description = "Search query")
    String query;

    @Option(names = "--limit", defaultValue = "5")
    int limit;

    @Option(names = "--mode", defaultValue = "HYBRID", description = "HYBRID, KEYWORD or VECTOR")
    CodeIndex.SearchMode mode;

    @Override
    public Integer call() {
      try {
        var report = index(List.of()).search(query, limit, mode);
        var text =
            new StringBuilder(
                "model="
                    + report.embeddingModel()
                    + (report.degraded() ? " [keyword fallback]" : "")
                    + (report.stale() ? " [stale index]" : ""));
        for (var hit : report.hits())
          text.append('\n')
              .append(hit.path())
              .append(':')
              .append(hit.startLine())
              .append('-')
              .append(hit.endLine())
              .append(' ')
              .append(hit.symbol())
              .append(" score=")
              .append(String.format(java.util.Locale.ROOT, "%.4f", hit.score()));
        reporter().success(report, text.toString());
        return 0;
      } catch (IllegalStateException | IllegalArgumentException e) {
        throw new FlowException(1, e.getMessage());
      }
    }
  }
}
