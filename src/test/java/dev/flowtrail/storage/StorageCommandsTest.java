package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.command.StorageCommands;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class StorageCommandsTest {
  @TempDir Path root;

  @Test
  void commandsEmitSingleJsonDocumentWithSourceAndMockDisclosure() throws Exception {
    Files.writeString(
        root.resolve("Refund.java"), "class Refund { int refundOrder() { return 1; } }");
    var output = new StringWriter();
    var index = new CommandLine(new StorageCommands.Index()).setOut(new PrintWriter(output));
    assertEquals(0, index.execute("--project", root.toString(), "--json"));
    assertTrue(new ObjectMapper().readTree(output.toString()).path("mock").asBoolean());
    output.getBuffer().setLength(0);
    var search = new CommandLine(new StorageCommands.Search()).setOut(new PrintWriter(output));
    assertEquals(0, search.execute("refundOrder", "--project", root.toString(), "--json"));
    var result = new ObjectMapper().readTree(output.toString());
    assertEquals("Refund.java", result.path("hits").path(0).path("path").asText());
    assertTrue(result.has("timings"));
  }
}
