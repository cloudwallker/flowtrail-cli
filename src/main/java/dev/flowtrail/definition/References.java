package dev.flowtrail.definition;

import dev.flowtrail.FlowException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class References {
  private static final Pattern REFERENCE =
      Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)\\.output}");

  private References() {}

  public static void validate(String text, Set<String> previous) {
    Matcher matcher = REFERENCE.matcher(text);
    while (matcher.find()) {
      if (!previous.contains(matcher.group(1))) {
        throw new FlowException(2, "References must point to an earlier step's output.");
      }
    }
    if (REFERENCE.matcher(text).replaceAll("").contains("${")) {
      throw new FlowException(2, "Invalid reference. Use ${stepId.output}.");
    }
  }

  public static String resolve(String text, Map<String, String> outputs) {
    Matcher matcher = REFERENCE.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String replacement = outputs.get(matcher.group(1));
      if (replacement == null) {
        throw new FlowException(2, "Referenced output is not available.");
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
