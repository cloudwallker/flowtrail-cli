package dev.flowtrail.command;

import dev.flowtrail.FlowException;
import dev.flowtrail.definition.FlowLoader;
import dev.flowtrail.execution.FlowRunner;
import dev.flowtrail.output.Reporter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

public final class Commands {
  private Commands() {}

  public abstract static class OutputCommand implements Callable<Integer> {
    @Option(names = "--json", description = "Emit machine-readable JSON.")
    boolean json;

    @Spec CommandSpec spec;

    Reporter reporter() {
      return new Reporter(spec.commandLine().getOut(), spec.commandLine().getErr(), json);
    }
  }

  @Command(
      name = "init",
      mixinStandardHelpOptions = true,
      description = "Create an offline example without overwriting files.")
  public static final class Init extends OutputCommand {
    @Parameters(index = "0", arity = "0..1", defaultValue = "flow.json", paramLabel = "FILE")
    Path file;

    @Override
    public Integer call() throws IOException {
      String example =
          """
                    {
                      "name": "hello-flowtrail",
                      "steps": [
                        { "id": "greeting", "type": "text", "text": "Hello, FlowTrail!" },
                        { "id": "summary", "type": "text", "text": "Previous output: ${greeting.output}" }
                      ]
                    }
                    """;
      try {
        Files.writeString(file, example, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException exception) {
        throw new FlowException(1, "Task file already exists. Choose another path.");
      }
      reporter().success(Map.of("ok", true, "file", file.toString()), "Created " + file);
      return 0;
    }
  }

  @Command(
      name = "validate",
      mixinStandardHelpOptions = true,
      description = "Validate a task without executing it.")
  public static final class Validate extends OutputCommand {
    @Parameters(index = "0", paramLabel = "FILE")
    Path file;

    @Override
    public Integer call() {
      var flow = new FlowLoader().load(file);
      reporter()
          .success(
              Map.of("ok", true, "name", flow.name(), "stepCount", flow.steps().size()),
              "Valid: " + flow.name() + " (" + flow.steps().size() + " steps)");
      return 0;
    }
  }

  @Command(
      name = "run",
      mixinStandardHelpOptions = true,
      description = "Validate and run steps in order; stop on the first failure.")
  public static final class Run extends OutputCommand {
    @Parameters(index = "0", paramLabel = "FILE")
    Path file;

    @Override
    public Integer call() throws InterruptedException {
      var flow = new FlowLoader().load(file);
      var result = new FlowRunner().run(flow, reporter());
      reporter().success(result, "Completed " + result.steps().size() + " steps.");
      return 0;
    }
  }

  @Command(
      name = "doctor",
      mixinStandardHelpOptions = true,
      description = "Check Java and the working directory.")
  public static final class Doctor extends OutputCommand {
    @Override
    public Integer call() {
      boolean java21 = Runtime.version().feature() >= 21;
      boolean writable = Files.isWritable(Path.of("."));
      boolean ok = java21 && writable;
      if (!ok) {
        throw new FlowException(
            1, "Environment check failed: Java 21+ and a writable working directory are required.");
      }
      reporter()
          .success(
              Map.of(
                  "ok",
                  true,
                  "checks",
                  Map.of("java21", java21, "workingDirectoryWritable", writable)),
              "Java 21+: OK\nWorking directory writable: OK");
      return 0;
    }
  }
}
