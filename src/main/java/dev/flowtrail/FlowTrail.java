package dev.flowtrail;

import dev.flowtrail.command.AgentCommands;
import dev.flowtrail.command.Commands;
import dev.flowtrail.output.Reporter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "flowtrail",
    mixinStandardHelpOptions = true,
    version = "FlowTrail 0.2.0",
    description = "A bounded coding Agent, code search and repeatable task runner.",
    subcommands = {
      Commands.Init.class,
      Commands.Validate.class,
      Commands.Run.class,
      Commands.Doctor.class,
      AgentCommands.Agent.class,
      AgentCommands.Plan.class,
      AgentCommands.Mcp.class,
      dev.flowtrail.command.StorageCommands.Memory.class,
      dev.flowtrail.command.StorageCommands.Index.class,
      dev.flowtrail.command.StorageCommands.Search.class
    })
public final class FlowTrail {
  private FlowTrail() {}

  public static int execute(String[] args, PrintWriter out, PrintWriter err) {
    boolean json = false;
    for (String arg : args) {
      if (arg.equals("--")) {
        break;
      }
      if (arg.equals("--json")) {
        json = true;
      }
    }
    Reporter reporter = new Reporter(out, err, json);
    CommandLine cli = new CommandLine(new FlowTrail()).setOut(out).setErr(err);
    cli.setParameterExceptionHandler(
        (exception, arguments) -> {
          reporter.error(2, "Invalid arguments. Use flowtrail <command> --help.");
          return 2;
        });
    cli.setExecutionExceptionHandler(
        (exception, commandLine, parseResult) -> {
          if (exception instanceof FlowException failure) {
            reporter.error(failure.exitCode(), failure.getMessage());
            return failure.exitCode();
          }
          if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            reporter.error(130, "Execution interrupted.");
            return 130;
          }
          reporter.error(1, "Operation failed. Check file permissions and runtime configuration.");
          return 1;
        });
    return cli.execute(args);
  }

  public static void main(String[] args) {
    System.exit(
        execute(
            args,
            new PrintWriter(System.out, true, StandardCharsets.UTF_8),
            new PrintWriter(System.err, true, StandardCharsets.UTF_8)));
  }
}
