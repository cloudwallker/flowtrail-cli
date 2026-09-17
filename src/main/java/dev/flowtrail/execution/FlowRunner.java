package dev.flowtrail.execution;

import dev.flowtrail.definition.FlowDefinition;
import dev.flowtrail.definition.References;
import dev.flowtrail.output.Reporter;
import dev.flowtrail.step.HttpStep;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlowRunner {
  public RunResult run(FlowDefinition flow, Reporter reporter) throws InterruptedException {
    Map<String, String> outputs = new LinkedHashMap<>();
    List<StepResult> results = new ArrayList<>();
    HttpStep http = new HttpStep();
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    try {
      for (var step : flow.steps()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException();
        }
        String output =
            step.type().equals("text")
                ? References.resolve(step.text(), outputs)
                : http.execute(client, step, outputs);
        outputs.put(step.id(), output);
        results.add(new StepResult(step.id(), output));
        reporter.progress(step.id(), output);
      }
    } finally {
      client.shutdownNow();
    }
    return new RunResult(true, flow.name(), List.copyOf(results));
  }

  public record StepResult(String id, String output) {}

  public record RunResult(boolean ok, String name, List<StepResult> steps) {}
}
