package com.ntros;

import com.ntros.data.FileStorageSettings;
import com.ntros.data.ServerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);
  private static final int WEB_SERVER_PORT = 8989;
  private static final int SERVER_REQUEST_PROCESSOR_THREADS = 5;

  private static final Path BASE_DIR = Path.of("recordings");
  private static final Path TMP_DIR = Path.of("recordings/tmp");
  private static final Path READY_DIR = Path.of("recordings/ready");
  private static final Path LIVE_DIR = Path.of("recordings/live");
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

  static void main() {
    // config and run web server
    ServerSettings settings = new ServerSettings(WEB_SERVER_PORT, SERVER_REQUEST_PROCESSOR_THREADS);

    var fileSettings =
        new FileStorageSettings(BASE_DIR, TMP_DIR, READY_DIR, LIVE_DIR, DATE_TIME_FORMATTER);
    log.info("Starting server on port: {}", WEB_SERVER_PORT);
    RuntimeController controller = new RuntimeController(settings, fileSettings);

    attachShutdownHook(controller);
    controller.start();
    log.info("Accepting requests...");
  }

  private static void attachShutdownHook(RuntimeController controller) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    controller.stop();
                    //                    cleanup();
                  } catch (InterruptedException e) {
                    log.info("Error during runtime shutdown", e);
                  }
                },
                "shutdown-1"));
  }

  // TODO: add as a voice command, once feature dev is started
  private static void cleanup() {
    log.info("Deleting recordings...");
    try (Stream<Path> walk = Files.walk(BASE_DIR)) {
      // sort in reverse so the dir is always the last,
      // after the subdirs and files
      walk.sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(
              f -> {
                if (f.delete()) {
                  log.info("Deleted {}", f);
                }
              });
    } catch (IOException e) {
      log.error("Could not delete some recordings", e);
      throw new RuntimeException(e);
    }
  }
}
