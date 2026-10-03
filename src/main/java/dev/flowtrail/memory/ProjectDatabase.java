package dev.flowtrail.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** A database per canonical project; every table also scopes rows to that canonical path. */
public final class ProjectDatabase {
  private final Path root;
  private final Path directory;
  private final Path file;

  public ProjectDatabase(Path projectRoot) {
    try {
      root = projectRoot.toRealPath();
      if (!Files.isDirectory(root))
        throw new IllegalArgumentException("Project must be a directory");
      directory = root.resolve(".flowtrail");
      if (Files.isSymbolicLink(directory))
        throw new IllegalArgumentException("Storage cannot be a symbolic link");
      Files.createDirectories(directory);
      if (!directory.toRealPath().startsWith(root))
        throw new IllegalArgumentException("Storage outside project");
      file = directory.resolve("storage.sqlite");
      verifyStoragePath();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot open project storage", e);
    }
  }

  public Path root() {
    return root;
  }

  public String project() {
    return root.toString();
  }

  private void verifyStoragePath() throws IOException {
    if (Files.isSymbolicLink(directory)
        || !directory.toRealPath().startsWith(root)
        || Files.isSymbolicLink(file)
        || (Files.exists(file) && !file.toRealPath().startsWith(root))) {
      throw new IllegalArgumentException("Storage outside project");
    }
  }

  public Connection open() throws SQLException {
    try {
      verifyStoragePath();
    } catch (IOException e) {
      throw new SQLException("Cannot verify storage path", e);
    }
    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=5000");
      statement.execute("PRAGMA foreign_keys=ON");
    }
    return connection;
  }
}
