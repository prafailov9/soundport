package com.ntros.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioToTextTranscriber extends AbstractTranscriber {

  private static final Logger log = LoggerFactory.getLogger(AudioToTextTranscriber.class);

  private final Path exe;
  private final Path model;

  public AudioToTextTranscriber(Path exe, Path model) {
    this.exe = exe;
    this.model = model;
  }

  @Override
  public String transcribe(Path f) {
    Path wav = withExtension(f, ".wav");
    var command = buildFfmpegCommand(f, wav);
    run(command);

    Path outBase = withExtension(f, "");
    run(buildTranscribeCommand(wav, outBase));
    try {
      return Files.readString(withExtension(f, ".txt")).strip();
    } catch (IOException e) {
      log.error("Could not read {} file .", f, e);
      throw new RuntimeException(e);
    }
  }

  private List<String> buildFfmpegCommand(Path f, Path wav) {
    return List.of(
        "ffmpeg",
        "-y",
        "-i",
        f.toString(),
        "-ar",
        "16000",
        "-ac",
        "1",
        "-c:a",
        "pcm_s16le",
        wav.toString());
  }

  private List<String> buildTranscribeCommand(Path wav, Path outBase) {
    return List.of(
        exe.toString(),
        "-m",
        model.toString(),
        "-f",
        wav.toString(),
        "-l",
        "auto",
        "-nt",
        "-otxt",
        "-of",
        outBase.toString());
  }
}
