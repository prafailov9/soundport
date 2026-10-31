package com.ntros.transcription;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhisperServerTranscriber extends AbstractTranscriber {

  private static final Logger log = LoggerFactory.getLogger(WhisperServerTranscriber.class);
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
      return send(wav);
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Transcription failed for " + f, e);
    }
  }

  private String send(Path wav) throws IOException, InterruptedException {
    var request = buildWhisperRequest(wav);
    var response = client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IOException(
          "whisper-server returned " + response.statusCode() + ": " + response.body());
    }
    // splits lines as a text block
    return response.body().strip();
    // whole response is oneline
    //    return response.body().strip().replaceAll("\\s*\\n\\s*", " ");
  }

  private HttpRequest buildWhisperRequest(Path wav) throws FileNotFoundException {
    String boundary = "soundport-" + UUID.randomUUID();
    return HttpRequest.newBuilder(inferenceUri)
        .timeout(Duration.ofMinutes(2))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(
            BodyPublishers.concat(
                BodyPublishers.ofString(buildHead(boundary, wav), StandardCharsets.UTF_8),
                BodyPublishers.ofFile(wav), // streams the file, no copy in memory
                BodyPublishers.ofString(buildTail(boundary), StandardCharsets.UTF_8)))
        .build();
  }

  private String buildHead(String boundary, Path wavFileName) {
    return "--"
        + boundary
        + "\r\n"
        + "Content-Disposition: form-data; name=\"file\"; filename=\""
        + wavFileName
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
