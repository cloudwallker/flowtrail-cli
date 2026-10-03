package dev.flowtrail.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowtrail.FlowException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** OpenAI SSE and Ollama NDJSON are deliberately parsed separately. */
public final class HttpModelProvider implements ModelProvider {
  private final boolean ollama;
  private final URI endpoint;
  private final String model, key;
  private final Duration timeout;

  public HttpModelProvider(
      String kind, String baseUrl, String model, String key, Duration timeout) {
    this.ollama = kind.equals("ollama");
    URI base = URI.create(baseUrl);
    if (!List.of("http", "https").contains(base.getScheme()) || base.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid provider URL");
    endpoint =
        URI.create(baseUrl.replaceAll("/$", "") + (ollama ? "/api/chat" : "/chat/completions"));
    this.model = model;
    this.key = key;
    this.timeout = timeout;
  }

  @Override
  public Response complete(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> output)
      throws Exception {
    List<Map<String, Object>> wire = ollama ? ollamaMessages(messages) : messages;
    Map<String, Object> payload =
        new LinkedHashMap<>(Map.of("model", model, "messages", wire, "stream", true));
    if (!tools.isEmpty()) payload.put("tools", tools);
    if (ollama) payload.put("options", Map.of("num_predict", 4096));
    else payload.put("max_tokens", 4096);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.encode(payload)));
    if (key != null && !key.isBlank()) request.header("Authorization", "Bearer " + key);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    AtomicReference<java.io.InputStream> body = new AtomicReference<>();
    var future =
        executor.submit(
            () -> {
              try (var client =
                  HttpClient.newBuilder()
                      .connectTimeout(timeout)
                      .followRedirects(HttpClient.Redirect.NEVER)
                      .build()) {
                var response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                body.set(response.body());
                try (var stream = response.body()) {
                  if (response.statusCode() / 100 != 2)
                    throw new FlowException(
                        1, "Model provider returned HTTP " + response.statusCode());
                  return parse(stream, output);
                }
              }
            });
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof FlowException f) throw f;
      throw new FlowException(1, "Model provider protocol or connection failed");
    } catch (java.util.concurrent.TimeoutException e) {
      throw new FlowException(1, "Model provider timed out");
    } finally {
      future.cancel(true);
      if (body.get() != null)
        try {
          body.get().close();
        } catch (Exception ignored) {
        }
      executor.shutdownNow();
    }
  }

  private Response parse(java.io.InputStream input, Consumer<String> output) throws Exception {
    StringBuilder text = new StringBuilder();
    Map<Integer, Builder> calls = new TreeMap<>();
    int chars = 0;
    boolean done = false;
    try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = readBoundedLine(reader, 1_000_000 - chars)) != null) {
        chars += line.length() + 1;
        if (chars > 1_000_000) throw new IllegalArgumentException("Response exceeds budget");
        if (line.isBlank() || line.startsWith(":")) continue;
        if (!ollama) {
          if (!line.startsWith("data:")) continue;
          line = line.substring(5).stripLeading();
          if (line.equals("[DONE]")) {
            done = true;
            break;
          }
        }
        JsonNode event = Json.MAPPER.readTree(line);
        if (event.has("error")) throw new IllegalArgumentException();
        JsonNode delta =
            ollama ? event.path("message") : event.path("choices").path(0).path("delta");
        String fragment = delta.path("content").asText("");
        text.append(fragment);
        output.accept(fragment);
        int n = 0;
        for (JsonNode call : delta.path("tool_calls")) {
          int index = ollama ? n++ : call.path("index").asInt(-1);
          if (index < 0 || index > 63) throw new IllegalArgumentException();
          Builder b = calls.computeIfAbsent(index, k -> new Builder());
          if (call.has("id")) b.id.append(call.path("id").asText());
          JsonNode f = call.path("function");
          if (f.has("name")) b.name.append(f.path("name").asText());
          if (f.has("arguments")) {
            if (ollama) b.args.append(f.path("arguments").toString());
            else b.args.append(f.path("arguments").asText());
          }
        }
        if (ollama && event.path("done").asBoolean()) {
          done = true;
          break;
        }
      }
    }
    if (!done) throw new IllegalArgumentException("Incomplete model stream");
    List<ToolCall> result = new ArrayList<>();
    for (var entry : calls.entrySet()) {
      Builder b = entry.getValue();
      String id = b.id.isEmpty() ? "ollama_" + entry.getKey() : b.id.toString();
      if (b.name.isEmpty()) throw new IllegalArgumentException();
      result.add(new ToolCall(id, b.name.toString(), b.args.toString()));
    }
    return new Response(text.toString(), result);
  }

  private String readBoundedLine(BufferedReader reader, int remaining) throws Exception {
    StringBuilder line = new StringBuilder();
    int value;
    while ((value = reader.read()) != -1) {
      if (value == '\n') return line.toString();
      if (line.length() >= remaining) throw new IllegalArgumentException("Response exceeds budget");
      line.append((char) value);
    }
    return line.isEmpty() ? null : line.toString();
  }

  private List<Map<String, Object>> ollamaMessages(List<Map<String, Object>> messages)
      throws Exception {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> message : messages) {
      Map<String, Object> copy = new LinkedHashMap<>(message);
      if ("tool".equals(copy.get("role"))) {
        copy.put("tool_name", copy.remove("name"));
        copy.remove("tool_call_id");
      }
      if (copy.containsKey("tool_calls")) {
        JsonNode calls = Json.MAPPER.valueToTree(copy.get("tool_calls"));
        List<Object> converted = new ArrayList<>();
        for (JsonNode c : calls) {
          converted.add(
              Map.of(
                  "function",
                  Map.of(
                      "name",
                      c.path("function").path("name").asText(),
                      "arguments",
                      Json.MAPPER.readTree(c.path("function").path("arguments").asText()))));
        }
        copy.put("tool_calls", converted);
      }
      result.add(copy);
    }
    return result;
  }

  private static final class Builder {
    final StringBuilder id = new StringBuilder(),
        name = new StringBuilder(),
        args = new StringBuilder();
  }
}
