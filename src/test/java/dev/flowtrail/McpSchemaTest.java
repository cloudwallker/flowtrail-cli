package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.mcp.SchemaValidator;
import org.junit.jupiter.api.Test;

class McpSchemaTest {
  @Test
  void maxLengthCountsSupplementaryCharactersOnce() throws Exception {
    var schema = AgentCommandsTest.JSON.readTree("{\"type\":\"string\",\"maxLength\":1}");
    var value = AgentCommandsTest.JSON.getNodeFactory().textNode("\uD83D\uDE00");
    assertDoesNotThrow(() -> SchemaValidator.validate(schema, value));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaValidator.validate(
                schema, AgentCommandsTest.JSON.getNodeFactory().textNode("\uD83D\uDE00a")));
  }

  @Test
  void minLengthDoesNotCountSurrogatePairsAsTwoCharacters() throws Exception {
    var schema = AgentCommandsTest.JSON.readTree("{\"type\":\"string\",\"minLength\":2}");
    var value = AgentCommandsTest.JSON.getNodeFactory().textNode("\uD83D\uDE00");
    assertThrows(IllegalArgumentException.class, () -> SchemaValidator.validate(schema, value));
    assertDoesNotThrow(
        () ->
            SchemaValidator.validate(
                schema, AgentCommandsTest.JSON.getNodeFactory().textNode("\uD83D\uDE00a")));
  }

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
