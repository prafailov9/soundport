package com.ntros.transcription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractTranscriber implements Transcriber {
  private static final Logger log = LoggerFactory.getLogger(AbstractTranscriber.class);

  protected void runFfmpeg(Path in, Path wav) throws IOException, InterruptedException {
    List<String> command =
        List.of(
            "ffmpeg",
            "-y",
            "-i",
            in.toString(),
            "-ar",
            "16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            wav.toString());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IOException("ffmpeg failed:\n" + out);
    }
  }

  protected void run(List<String> command) {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        String err = command.getFirst() + " failed (exit " + exitCode + "):\n" + out;
        log.error(err);
        throw new IOException(err);
      }
      log.debug("{} output:\n{}", command.getFirst(), out);
    } catch (IOException | InterruptedException e) {
      log.error("Failed during transcription", e);
      throw new RuntimeException(e);
    }
  }

  protected Path withExtension(Path file, String ext) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return file.resolveSibling((dot >= 0 ? name.substring(0, dot) : name) + ext);
  }
}
