package dev.flowtrail.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifier/camel-case terms and Chinese bigrams; no network or dictionary download. */
public final class KeywordTokenizer {
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

  private KeywordTokenizer() {}

  public static List<String> terms(String text) {
    var result = new LinkedHashSet<String>();
    var matcher = WORD.matcher(text);
    while (matcher.find()) {
      String original = matcher.group();
      result.add(original.toLowerCase(Locale.ROOT));
      for (String part : original.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[\\s_]+")) {
        if (!part.isBlank()) result.add(part.toLowerCase(Locale.ROOT));
      }
      int[] chars = original.codePoints().toArray();
      for (int i = 0; i + 1 < chars.length; i++) {
        if (Character.UnicodeScript.of(chars[i]) == Character.UnicodeScript.HAN
            && Character.UnicodeScript.of(chars[i + 1]) == Character.UnicodeScript.HAN) {
          result.add(new String(chars, i, 2));
        }
      }
    }
    return List.copyOf(result);
  }
}
