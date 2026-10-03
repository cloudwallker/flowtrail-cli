package dev.flowtrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public interface McpTransport extends AutoCloseable {
  JsonNode exchange(JsonNode message) throws Exception;

  void version(String protocolVersion);

  @Override
  void close();
}
