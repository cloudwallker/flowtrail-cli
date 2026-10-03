package dev.flowtrail.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.List;

final class CodeChunker {
  static final String VERSION = "javaparser-lines-v1";
  private static final int MAX_CHARS = 6_000;

  record Chunk(
      String path, String symbol, String kind, int startLine, int endLine, String content) {}

  List<Chunk> split(String path, String content) {
    String[] lines = content.split("\\R", -1);
    var result = new ArrayList<Chunk>();
    if (!path.endsWith(".java")) {
      add(result, path, path, "text", lines, 1, lines.length);
      return result;
    }
    var parsed =
        new JavaParser(
                new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
            .parse(content);
    if (!parsed.isSuccessful() || parsed.getResult().isEmpty())
      throw new IllegalArgumentException("Java parse failed");
    var unit = parsed.getResult().orElseThrow();
    for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
      int start = type.getBegin().orElseThrow().line;
      int end = type.getEnd().orElseThrow().line;
      int headerEnd =
          type.getMembers().isEmpty()
              ? end
              : Math.max(start, type.getMembers().get(0).getBegin().orElseThrow().line - 1);
      add(result, path, type.getNameAsString(), "type", lines, start, headerEnd);
      for (var member : type.getMembers()) {
        if (member instanceof TypeDeclaration<?>) continue;
        String symbol = type.getNameAsString();
        String kind = "member";
        if (member instanceof CallableDeclaration<?> callable) {
          symbol += "#" + callable.getSignature();
          kind = "method";
        }
        int memberStart = member.getBegin().orElseThrow().line;
        if (member.getComment().isPresent())
          memberStart = member.getComment().get().getBegin().orElseThrow().line;
        add(result, path, symbol, kind, lines, memberStart, member.getEnd().orElseThrow().line);
      }
    }
    if (result.isEmpty()) add(result, path, path, "java", lines, 1, lines.length);
    return result;
  }

  private static void add(
      List<Chunk> output,
      String path,
      String symbol,
      String kind,
      String[] lines,
      int start,
      int end) {
    StringBuilder chunk = new StringBuilder();
    int chunkStart = start;
    for (int line = start; line <= end; line++) {
      String value = lines[line - 1];
      if (!chunk.isEmpty() && chunk.length() + value.length() + 1 > MAX_CHARS) {
        output.add(new Chunk(path, symbol, kind, chunkStart, line - 1, chunk.toString()));
        chunk.setLength(0);
        chunkStart = line;
      }
      if (value.length() > MAX_CHARS) {
        for (int offset = 0; offset < value.length(); offset += MAX_CHARS)
          output.add(
              new Chunk(
                  path,
                  symbol,
                  kind,
                  line,
                  line,
                  value.substring(offset, Math.min(value.length(), offset + MAX_CHARS))));
        chunkStart = line + 1;
      } else {
        chunk.append(value).append('\n');
      }
    }
    if (!chunk.isEmpty())
      output.add(new Chunk(path, symbol, kind, chunkStart, end, chunk.toString()));
  }
}
