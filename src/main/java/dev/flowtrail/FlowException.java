package dev.flowtrail;

public final class FlowException extends RuntimeException {
  private final int exitCode;

  public FlowException(int exitCode, String message) {
    super(message);
    this.exitCode = exitCode;
  }

  public int exitCode() {
    return exitCode;
  }
}
