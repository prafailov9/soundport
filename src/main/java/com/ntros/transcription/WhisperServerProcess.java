package com.ntros.transcription;

import com.ntros.LifeCycle;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WhisperServerProcess implements LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(WhisperServerProcess.class);
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
  private static final String PROSE_WITH_LOTS_OF_PUNCTUATION =
      "The wind howled through the dark, deserted streets; trees swayed violently, windows rattled, and somewhere in the distance, a dog barked—once, twice, then silence. Suddenly, a deafening crash echoed through the night: what was that?! Emily froze, her heart pounding; she glanced over her shoulder, but there was nothing—nothing except shadows, mist, and the eerie glow of a flickering streetlamp. “Who's there?” she whispered, trembling; no answer came... only the soft, unsettling sound of footsteps: tap, tap, tap! Then, without warning, a figure emerged from the darkness—and Emily screamed, “RUN!” “RUN!” Emily screamed—but suddenly, the mysterious figure burst into laughter, shouting, “Run?! No, no, no! Come dance!” To her astonishment, the dark clouds parted, revealing a sky bursting with glittering stars; colourful lanterns flickered to life, cheerful music filled the air, and dozens of familiar faces leapt from the shadows, yelling, “SURPRISE!!!” Emily gasped—then laughed with pure delight—as she realised her friends and family had organised the most spectacular birthday celebration imaginable! The once-frightening footsteps became the rhythmic tapping of dancing shoes; the howling wind turned into a gentle, playful breeze, carrying the sweet scent of freshly baked cakes, blooming roses, and warm cinnamon. “This is the BEST NIGHT EVER!” Emily cried, spinning beneath a shower of golden confetti; and as fireworks exploded overhead—BOOM! SPARKLE! WHOOSH!—she knew, without a doubt, that sometimes the most terrifying beginnings lead to the happiest endings!";
  private static final int WAIT_UNTIL_PROCESS_READY_DELAY_MS = 200;

  private final Path exe;
  private final Path model;
  private final int port;
  private final Path logFile;
  private final HttpClient client = HttpClient.newHttpClient();
  private Process process;

  public WhisperServerProcess(Path exe, Path model, int port, Path logFile) {
    this.exe = exe;
    this.model = model;
    this.port = port;
    this.logFile = logFile;
  }

  public URI inferenceUri() {
    return URI.create("http://127.0.0.1:" + port + "/inference");
  }

  @Override
  public void start() {
    if (isResponding()) {
      log.warn("whisper-server already running on port {}, reusing it", port);
      return;
    }
    try {
      process =
          new ProcessBuilder(buildCommand())
              .redirectErrorStream(true)
              .redirectOutput(
                  logFile.toFile()) // its output goes to a file, so it can never fill up and freeze
              .start();
    } catch (IOException e) {
      throw new RuntimeException("Could not start whisper-server", e);
    }

    log.info("Started whisper-server (pid {}), loading model...", process.pid());
    waitUntilReady();
    log.info("whisper-server ready on port {}", port);
  }

  @Override
  public void stop() throws InterruptedException {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
    }
    log.info("whisper-server stopped");
  }

  private void waitUntilReady() {
    long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "whisper-server exited with code " + process.exitValue() + ", see " + logFile);
      }
      if (isResponding()) {
        return;
      }
      delay();
    }
    process.destroy();
    throw new IllegalStateException(
        "whisper-server not ready after " + STARTUP_TIMEOUT + ", see " + logFile);
  }

  private void delay() {
    try {
      Thread.sleep(WAIT_UNTIL_PROCESS_READY_DELAY_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private boolean isResponding() {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
            .timeout(Duration.ofSeconds(1))
            .GET()
            .build();
    try {
      client.send(request, BodyHandlers.discarding());
      return true; // any HTTP answer means it's up
    } catch (IOException e) {
      return false; // nothing listening yet
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private List<String> buildCommand() {
    return List.of(
        exe.toString(),
        "-m",
        model.toString(),
        "--host",
        "127.0.0.1",
        "--port",
        String.valueOf(port),
        "-l",
        "auto",
        "--prompt",
        PROSE_WITH_LOTS_OF_PUNCTUATION);
  }
}
