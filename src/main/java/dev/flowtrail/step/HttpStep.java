package dev.flowtrail.step;

import dev.flowtrail.FlowException;
import dev.flowtrail.definition.FlowDefinition.Step;
import dev.flowtrail.definition.FlowLoader;
import dev.flowtrail.definition.References;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class HttpStep {
  public String execute(HttpClient client, Step step, Map<String, String> outputs)
      throws InterruptedException {
    var request =
        HttpRequest.newBuilder(FlowLoader.validateUrl(References.resolve(step.url(), outputs)))
            .timeout(Duration.ofSeconds(step.timeoutSeconds()));
    step.headers()
        .forEach(
            (key, value) -> {
              String resolved = References.resolve(value, outputs);
              FlowLoader.validateHeader(key, resolved);
              request.header(key, resolved);
            });
    if (step.method().equals("POST")) {
      request.POST(
          HttpRequest.BodyPublishers.ofString(
              References.resolve(step.body(), outputs), StandardCharsets.UTF_8));
    } else {
      request.GET();
    }
    var responseFuture =
        client.sendAsync(
            request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    try {
      HttpResponse<String> response = responseFuture.get(step.timeoutSeconds(), TimeUnit.SECONDS);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new FlowException(
            1, "Step " + step.id() + " failed with HTTP " + response.statusCode() + ".");
      }
      return response.body();
    } catch (TimeoutException exception) {
      throw new FlowException(1, "Step " + step.id() + " timed out.");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof HttpTimeoutException) {
        throw new FlowException(1, "Step " + step.id() + " timed out.");
      }
      throw new FlowException(1, "Step " + step.id() + " could not complete the HTTP request.");
    } finally {
      responseFuture.cancel(true);
    }
  }
}
