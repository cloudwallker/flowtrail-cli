package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.mcp.SchemaValidator;
import org.junit.jupiter.api.Test;

class McpSchemaTest {
  @Test
  void additionalPropertiesSchemaRejectsWrongTypedUnknownFields() throws Exception {
    var schema =
        AgentCommandsTest.JSON.readTree(
            "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}");
    var wrong = AgentCommandsTest.JSON.readTree("{\"x\":\"wrong\"}");
    assertThrows(IllegalArgumentException.class, () -> SchemaValidator.validate(schema, wrong));
    assertDoesNotThrow(
        () -> SchemaValidator.validate(schema, AgentCommandsTest.JSON.readTree("{\"x\":12}")));
  }
}
