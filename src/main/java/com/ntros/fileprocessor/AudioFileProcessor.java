package com.ntros.fileprocessor;

import com.ntros.data.FileStorageSettings;
import com.ntros.data.UploadRequest;
import com.ntros.data.WriteFileResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioFileProcessor implements FileProcessor {

  private static final Logger log = LoggerFactory.getLogger(AudioFileProcessor.class);

  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

  private final FileStorageSettings settings;

  public AudioFileProcessor(FileStorageSettings settings) {
    if (settings == null) {
      throw new IllegalArgumentException("file settings empty");
    }

    this.settings = settings;

    createDirs();
  }

  @Override
  public WriteFileResult write(UploadRequest uploadRequest) {
    var contentType = uploadRequest.contentType();
    var is = uploadRequest.is();
    boolean saved = false;

    String fileName = LocalDateTime.now().format(FILE_TIME) + extensionFor(contentType);
    Path tmp = settings.tmpDir().resolve(fileName);
    Path rdy = settings.readyDir().resolve(fileName);
    try {

      // write to temp first
      long fileSize = Files.copy(is, tmp);
      // atomic_move to ready once file is written
      Files.move(tmp, rdy, StandardCopyOption.ATOMIC_MOVE);

      log.info("Saved {} ({} bytes, {})", rdy, fileSize, contentType);
      saved = true;
    } catch (IOException e) {
      log.error("Failed during file save", e);
    }

    return WriteFileResult.of(saved, rdy);
  }

  // TODO: move to classifier class

  private static String extensionFor(String contentType) {
    if (contentType == null) return ".bin";
    if (contentType.startsWith("audio/mp4")) return ".m4a";
    if (contentType.startsWith("audio/webm")) return ".webm";
    if (contentType.startsWith("audio/ogg")) return ".ogg";
    return ".bin";
  }

  private void createDirs() {
    try {
      Files.createDirectories(settings.baseDir());
      Files.createDirectories(settings.tmpDir());
      Files.createDirectories(settings.readyDir());
    } catch (IOException e) {
      log.error("Failed to create dir", e);
      throw new RuntimeException(e);
    }
  }
}
