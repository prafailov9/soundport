package com.ntros.transcription;

import com.ntros.data.Wav;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhisperServerTranscriber extends AbstractTranscriber {

  private static final Logger log = LoggerFactory.getLogger(WhisperServerTranscriber.class);
  // Whisper's "nothing here" markers, e.g. [BLANK_AUDIO], (silence), [Music]
  private static final Pattern MARKER = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)");
  private final URI inferenceUri;
  private final HttpClient client = HttpClient.newHttpClient();

  public WhisperServerTranscriber(URI inferenceUri) {
    this.inferenceUri = inferenceUri;
  }

  @Override
  public String transcribe(Path f) {
    try {
      Path wav = withExtension(f, ".wav");
      runFfmpeg(f, wav);
      // streams the file, no copy in memory
      return send(BodyPublishers.ofFile(wav), wav.getFileName().toString());
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Transcription failed for " + f, e);
    }
  }

  /**
   * Raw 16 kHz mono 16-bit PCM straight from memory, no ffmpeg and no temp file. Always returns a
   * single line, since a live segment is part of a running sentence.
   */
  public String transcribePcm(byte[] pcm) {
    try {
      String text = send(BodyPublishers.ofByteArray(Wav.toWav(pcm)), "live.wav");
      return text.replaceAll("\\s*\\n\\s*", " ");
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Live transcription failed", e);
    }
  }

  private String send(BodyPublisher audio, String fileName)
      throws IOException, InterruptedException {
    var request = buildWhisperRequest(audio, fileName);
    var response = client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IOException(
          "whisper-server returned " + response.statusCode() + ": " + response.body());
    }
    // splits lines as a text block
    return stripMarkers(response.body()).strip();
  }

  /** Removes the markers, then the double spaces they leave behind. Line breaks are kept. */
  static String stripMarkers(String text) {
    return MARKER.matcher(text).replaceAll("").replaceAll("[ \\t]{2,}", " ");
  }

  private HttpRequest buildWhisperRequest(BodyPublisher audio, String fileName) {
    String boundary = "soundport-" + UUID.randomUUID();
    return HttpRequest.newBuilder(inferenceUri)
        .timeout(Duration.ofMinutes(2))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(
            BodyPublishers.concat(
                BodyPublishers.ofString(buildHead(boundary, fileName), StandardCharsets.UTF_8),
                audio,
                BodyPublishers.ofString(buildTail(boundary), StandardCharsets.UTF_8)))
        .build();
  }

  private String buildHead(String boundary, String fileName) {
    return "--"
        + boundary
        + "\r\n"
        + "Content-Disposition: form-data; name=\"file\"; filename=\""
        + fileName
        + "\"\r\n"
        + "Content-Type: audio/wav\r\n\r\n";
  }

  private String buildTail(String boundary) {
    return "\r\n--"
        + boundary
        + "\r\n"
        + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
        + "text\r\n"
        + "--"
        + boundary
        + "--\r\n";
  }
}
