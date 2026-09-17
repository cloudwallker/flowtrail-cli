package dev.flowtrail.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.util.Map;

public record Reporter(PrintWriter out, PrintWriter err, boolean json) {
  private static final ObjectMapper JSON = new ObjectMapper();

  public void success(Object value, String message) {
    out.println(json ? encode(value) : message);
    out.flush();
  }

  public void progress(String id, String output) {
    if (!json) {
      out.println("[" + id + "] " + output);
      out.flush();
    }
  }

  public void error(int code, String message) {
    err.println(
        json
            ? encode(Map.of("ok", false, "exitCode", code, "error", message))
            : "Error: " + message);
    err.flush();
  }

  private String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize command result", exception);
    }
  }
}
