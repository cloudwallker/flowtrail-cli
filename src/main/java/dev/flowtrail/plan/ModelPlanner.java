package dev.flowtrail.plan;

import dev.flowtrail.model.ModelProvider;
import java.util.List;
import java.util.Map;

public final class ModelPlanner {
  private final ModelProvider provider;

  public ModelPlanner(ModelProvider provider) {
    this.provider = provider;
  }

  public PlanDefinition create(String goal) throws Exception {
    var response =
        provider.complete(
            List.of(
                Map.of(
                    "role",
                    "system",
                    "content",
                    "Return only a version 1 plan JSON object with tasks. Each task has id, goal,"
                        + " dependsOn array, tools array, expectedOutput. Dependencies form a DAG."
                        + " Valid built-in tools: read_file, list_files, search_code, apply_patch,"
                        + " run_command. Refer to ancestor outputs as ${id.output}. No permissions"
                        + " can be granted by this plan."),
                Map.of("role", "user", "content", goal)),
            List.of(),
            s -> {});
    if (!response.toolCalls().isEmpty())
      throw new IllegalArgumentException("Planner returned unexpected tools");
    return PlanDefinition.parse(response.content());
  }
}
