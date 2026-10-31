package com.ntros.data;

import java.nio.file.Path;

public class WriteFileResult {

  private final boolean success;
  private final Path createdFile;

  private WriteFileResult(boolean success, Path createdFile) {
    this.createdFile = createdFile;
    this.success = success;
  }

  public static WriteFileResult of(boolean success, Path createdFile) {
    return new WriteFileResult(success, createdFile);
  }

  public static WriteFileResult ofCreated(Path f) {
    return new WriteFileResult(true, f);
  }

  public static WriteFileResult ofFailed(Path f) {
    return new WriteFileResult(false, f);
  }

  public boolean isSuccess() {
    return success;
  }

  public Path getCreatedFile() {
    return createdFile;
  }
}
