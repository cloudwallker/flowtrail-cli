package dev.flowtrail.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Execution constraints, not an operating-system sandbox. Immutable and never model-controlled. */
public final class ExecutionPolicy {
  private final Path root;
  private final boolean allowWrite, allowRemote;
  private final Set<List<String>> commands;

  public ExecutionPolicy(
      Path root, boolean allowWrite, boolean allowRemote, Set<List<String>> commands)
      throws IOException {
    this.root = root.toRealPath();
    this.allowWrite = allowWrite;
    this.allowRemote = allowRemote;
    this.commands = Set.copyOf(commands);
  }

  public Path root() {
    return root;
  }

  public boolean allowWrite() {
    return allowWrite;
  }

  public boolean allowRemote() {
    return allowRemote;
  }

  public boolean allowsCommand(List<String> command) {
    return commands.contains(command);
  }

  public Path resolve(String raw) throws IOException {
    if (raw == null || raw.isBlank())
      throw new SecurityException("A project-relative path is required");
    Path path = root.resolve(raw).normalize();
    if (!path.startsWith(root)) throw new SecurityException("Path is outside project");
    checkProtected(path);
    Path ancestor = path;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS))
      ancestor = ancestor.getParent();
    if (ancestor == null) throw new SecurityException("Path resolves outside project");
    Path real = ancestor.toRealPath();
    if (!real.startsWith(root)) throw new SecurityException("Path resolves outside project");
    checkProtected(real);
    return path;
  }

  private void checkProtected(Path path) {
    for (Path segment : root.relativize(path)) {
      String name = segment.toString().toLowerCase(java.util.Locale.ROOT);
      if (Set.of(".git", ".flowtrail", ".aws", ".ssh", ".codex", ".agents").contains(name)
          || name.equals(".env")
          || name.startsWith(".env.")
          || name.endsWith(".pem")
          || name.endsWith(".key")) throw new SecurityException("Protected project path");
    }
  }
}
