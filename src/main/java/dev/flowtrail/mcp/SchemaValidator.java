package dev.flowtrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Set;

/** Fail-closed subset of JSON Schema used by tool schemas; unsupported keywords are rejected. */
public final class SchemaValidator {
  private SchemaValidator() {}

  public static void validate(JsonNode schema, JsonNode value) {
    Set<String> supported =
        Set.of(
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "enum",
            "description",
            "title",
            "default",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "$schema");
    var keys = schema.fieldNames();
    while (keys.hasNext())
      if (!supported.contains(keys.next()))
        throw new IllegalArgumentException("Unsupported MCP schema keyword");
    String type = schema.path("type").asText();
    boolean valid =
        switch (type) {
          case "object" -> value.isObject();
          case "array" -> value.isArray();
          case "string" -> value.isTextual();
          case "integer" -> value.isIntegralNumber();
          case "number" -> value.isNumber();
          case "boolean" -> value.isBoolean();
          case "null" -> value.isNull();
          default -> false;
        };
    if (!valid) throw new IllegalArgumentException("MCP schema type mismatch");
    if (schema.has("enum")) {
      boolean match = false;
      for (JsonNode item : schema.get("enum")) if (item.equals(value)) match = true;
      if (!match) throw new IllegalArgumentException();
    }
    if (value.isObject()) {
      for (JsonNode required : schema.path("required"))
        if (!value.has(required.asText()))
          throw new IllegalArgumentException("Missing MCP argument");
      Iterator<String> fields = value.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        JsonNode property = schema.path("properties").get(field);
        if (property != null) validate(property, value.get(field));
        else if (schema.has("additionalProperties")) {
          JsonNode additional = schema.get("additionalProperties");
          if (additional.isObject()) validate(additional, value.get(field));
          else if (!additional.isBoolean() || !additional.asBoolean())
            throw new IllegalArgumentException("Unknown MCP argument");
        }
      }
    }
    if (value.isArray()) {
      if (value.size() < schema.path("minItems").asInt(0)
          || value.size() > schema.path("maxItems").asInt(10000))
        throw new IllegalArgumentException();
      if (schema.has("items")) for (JsonNode item : value) validate(schema.get("items"), item);
    }
    if (value.isTextual()
        && (value.asText().length() < schema.path("minLength").asInt(0)
            || value.asText().length() > schema.path("maxLength").asInt(65536)))
      throw new IllegalArgumentException();
    if (value.isNumber()
        && (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()
            || schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()))
      throw new IllegalArgumentException();
  }
}
