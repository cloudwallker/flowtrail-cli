package dev.flowtrail.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class Embeddings {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Embeddings() {}

  /** Deterministic offline fixture. This is explicitly NOT a learned semantic embedding. */
  public static EmbeddingProvider mock() {
    return new EmbeddingProvider() {
      public String modelId() {
        return "mock-hash-v1";
      }

      public int dimensions() {
        return 96;
      }

      public double[] embed(String text) {
        double[] values = new double[dimensions()];
        for (String term : KeywordTokenizer.terms(text))
          values[Math.floorMod(term.hashCode(), values.length)] += 1;
        if (norm(values) == 0) values[0] = 1;
        return values;
      }
    };
  }

  public static EmbeddingProvider fromEnvironment(String provider, String model, String baseUrl) {
    String selected = provider == null ? "mock" : provider.toLowerCase(Locale.ROOT);
    if (selected.equals("mock")) return mock();
    String dimensionsValue = System.getenv("FLOWTRAIL_EMBEDDING_DIMENSIONS");
    int dimensions =
        dimensionsValue == null
            ? (selected.equals("openai") ? 1536 : 768)
            : Integer.parseInt(dimensionsValue);
    if (selected.equals("openai")) {
      String key = System.getenv("OPENAI_API_KEY");
      if (key == null || key.isBlank()) key = System.getenv("FLOWTRAIL_API_KEY");
      if (key == null || key.isBlank())
        throw new IllegalArgumentException("OPENAI_API_KEY or FLOWTRAIL_API_KEY is required");
      return openAi(
          URI.create(baseUrl == null ? "https://api.openai.com/v1/" : baseUrl),
          model == null ? "text-embedding-3-small" : model,
          key,
          dimensions);
    }
    if (selected.equals("ollama"))
      return ollama(
          URI.create(baseUrl == null ? "http://localhost:11434/" : baseUrl),
          model == null ? "nomic-embed-text" : model,
          dimensions);
    throw new IllegalArgumentException("Embedding provider must be mock, openai or ollama");
  }

  public static EmbeddingProvider openAi(URI base, String model, String key, int dimensions) {
    return openAi(base, model, key, dimensions, Duration.ofSeconds(30));
  }

  public static EmbeddingProvider openAi(
      URI base, String model, String key, int dimensions, Duration timeout) {
    return new HttpEmbedding(base, model, key, dimensions, false, timeout);
  }

  public static EmbeddingProvider ollama(URI base, String model, int dimensions) {
    return new HttpEmbedding(base, model, null, dimensions, true, Duration.ofSeconds(30));
  }

  private static final class HttpEmbedding implements EmbeddingProvider {
    private final URI endpoint;
    private final String model;
    private final String key;
    private final int dimensions;
    private final boolean ollama;
    private final Duration timeout;
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpEmbedding(
        URI base, String model, String key, int dimensions, boolean ollama, Duration timeout) {
      if (dimensions < 1 || dimensions > 65536 || model == null || model.isBlank())
        throw new IllegalArgumentException("Invalid embedding model/dimensions");
      if (base.getHost() == null
          || base.getUserInfo() != null
          || base.getQuery() != null
          || base.getFragment() != null)
        throw new IllegalArgumentException("Invalid embedding URL");
      if (!"https".equals(base.getScheme())
          && !("http".equals(base.getScheme())
              && ("localhost".equals(base.getHost())
                  || "127.0.0.1".equals(base.getHost())
                  || "[::1]".equals(base.getHost()))))
        throw new IllegalArgumentException("Embedding URL requires HTTPS or local HTTP");
      String path = base.toString().replaceAll("/+$", "") + (ollama ? "/api/embed" : "/embeddings");
      if (timeout.isZero() || timeout.isNegative())
        throw new IllegalArgumentException("Embedding timeout must be positive");
      this.endpoint = URI.create(path);
      this.model = model;
      this.key = key;
      this.dimensions = dimensions;
      this.ollama = ollama;
      this.timeout = timeout;
    }

    public String modelId() {
      return (ollama ? "ollama:" : "openai:") + endpoint + ":" + model;
    }

    public int dimensions() {
      return dimensions;
    }

    public double[] embed(String text) throws Exception {
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(timeout)
              .header("Content-Type", "application/json");
      if (key != null) request.header("Authorization", "Bearer " + key);
      request.POST(
          HttpRequest.BodyPublishers.ofString(
              JSON.writeValueAsString(Map.of("model", model, "input", text))));
      var pending = client.sendAsync(request.build(), ignored -> limitedBody(8_000_000));
      HttpResponse<byte[]> response;
      try {
        response = pending.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        pending.cancel(true);
        throw new java.net.http.HttpTimeoutException("Embedding response timed out");
      } catch (InterruptedException e) {
        pending.cancel(true);
        throw e;
      }
      byte[] body = response.body();
      if (response.statusCode() < 200 || response.statusCode() > 299)
        throw new IllegalStateException("Embedding HTTP status " + response.statusCode());
      if (body.length > 8_000_000) throw new IllegalStateException("Embedding response too large");
      JsonNode result = JSON.readTree(body);
      JsonNode vector =
          ollama
              ? result.path("embeddings").path(0)
              : result.path("data").path(0).path("embedding");
      if (!vector.isArray() || vector.size() != dimensions)
        throw new IllegalStateException("Embedding dimension mismatch");
      double[] values = new double[dimensions];
      for (int i = 0; i < dimensions; i++) {
        if (!vector.get(i).isNumber()) throw new IllegalStateException("Invalid embedding value");
        values[i] = vector.get(i).asDouble();
      }
      validate(values, dimensions);
      return values;
    }
  }

  private static HttpResponse.BodySubscriber<byte[]> limitedBody(int maximumBytes) {
    var delegate = HttpResponse.BodySubscribers.ofByteArray();
    return new HttpResponse.BodySubscriber<>() {
      private java.util.concurrent.Flow.Subscription subscription;
      private long received;
      private boolean failed;

      public java.util.concurrent.CompletionStage<byte[]> getBody() {
        return delegate.getBody();
      }

      public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
        subscription = value;
        delegate.onSubscribe(value);
      }

      public void onNext(java.util.List<java.nio.ByteBuffer> items) {
        if (failed) return;
        for (var item : items) received += item.remaining();
        if (received > maximumBytes) {
          failed = true;
          subscription.cancel();
          delegate.onError(new java.io.IOException("Embedding response too large"));
        } else delegate.onNext(items);
      }

      public void onError(Throwable error) {
        if (!failed) {
          failed = true;
          delegate.onError(error);
        }
      }

      public void onComplete() {
        if (!failed) delegate.onComplete();
      }
    };
  }

  static void validate(double[] values, int dimensions) {
    if (values == null || values.length != dimensions)
      throw new IllegalStateException("Embedding dimension mismatch");
    for (double value : values)
      if (!Double.isFinite(value))
        throw new IllegalStateException("Embedding contains non-finite values");
    if (norm(values) == 0) throw new IllegalStateException("Embedding has zero norm");
  }

  static double norm(double[] values) {
    double total = 0;
    for (double value : values) total += value * value;
    return Math.sqrt(total);
  }
}
