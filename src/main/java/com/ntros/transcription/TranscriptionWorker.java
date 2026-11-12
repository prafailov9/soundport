package com.ntros.transcription;

import static com.ntros.data.Command.WRITE_2_SCREEN;

import com.ntros.LifeCycle;
import com.ntros.data.FileStorageSettings;
import com.ntros.data.UploadJob;
import com.ntros.output.TextOut;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranscriptionWorker implements Runnable, LifeCycle {
  private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

  private final FileStorageSettings fileStorageSettings;
  private final BlockingQueue<UploadJob> uploadQueue;
  private final BlockingQueue<TextOut> textQueue;
  // true if the live stream with this id already typed the text; the upload is then a duplicate
  private final Predicate<String> coveredByLive;
  private final Thread workerThread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Transcriber att;
  private final Transcriber transcriber;
  // file uploads report how they're doing here, so the page can fetch the result
  private final TranscriptionStatusService transcriptionStatusService;

  public TranscriptionWorker(
      FileStorageSettings fileStorageSettings,
      BlockingQueue<UploadJob> uploadQueue,
      BlockingQueue<TextOut> textQueue,
      Transcriber transcriber,
      TranscriptionStatusService transcriptionStatusService,
      Predicate<String> coveredByLive) {

    this.fileStorageSettings = fileStorageSettings;

    this.uploadQueue = uploadQueue;
    // TODO: move these strings to settings or a config file
    att =
        new AudioToTextTranscriber(
            Path.of("C:\\tools\\whisper-cuda\\Release\\whisper-cli.exe"), // adjust to where the exe
            // actually is
            Path.of("C:\\tools\\whisper-bin-x64\\models\\ggml-large-v3-turbo-q5_0.bin"));

    this.transcriber = transcriber;
    this.textQueue = textQueue;
    this.transcriptionStatusService = transcriptionStatusService;
    this.coveredByLive = coveredByLive;
    workerThread = new Thread(this, "upload-1");
  }

  @Override
  public void run() {
    while (running.get()) {
      UploadJob job = null;
      try {
        job = uploadQueue.take();
        if (job.command() == WRITE_2_SCREEN && coveredByLive.test(job.streamId())) {
          log.info("Live already typed {}, skipping the upload", job.file().getFileName());
          continue;
        }
        if (job.jobId() != null) {
          transcriptionStatusService.running(job.jobId());
        }
        // TODO: Add fallback to att

        String plaintext = transcriber.transcribe(job.file());
        // TODO: add command interpretation later on. If the plaintext contains a password(e.g. a
        // specific
        // sentence), watch for command words after it: cleanup current recordings, write the
        // following text in files, etc.
        if (job.command() == WRITE_2_SCREEN) {
          log.info("Transcribed:\n{}", plaintext);
          boolean unused = textQueue.offer(TextOut.upload(plaintext));
        } else {
          Path saved = null;
          try {
            saved = saveTranscript(job.file(), plaintext);
            log.info("Transcript saved: {}", saved);
          } catch (IOException e) {
            // the page still gets the text, which is what matters
            log.error("Saving the transcript of {} failed", job.file(), e);
          }
          if (job.jobId() != null) {
            transcriptionStatusService.done(job.jobId(), plaintext, saved);
          }
        }
      } catch (InterruptedException e) {
        break;
      } catch (RuntimeException e) {
        // never rethrow: an exception leaving run() ends this thread, and every later upload waits
        log.error("Transcription failed for {}", job == null ? "?" : job.file(), e);
        if (job != null && job.jobId() != null) {
          transcriptionStatusService.failed(job.jobId(), reason(e));
        }
      }
    }
  }

  @Override
  public void start() {
    workerThread.start();
  }

  @Override
  public void stop() throws InterruptedException {
    running.set(false);
    workerThread.interrupt(); // wakes take() so the loop can exit
    workerThread.join();
  }

  /** A short reason for the page; the full error, with ffmpeg's whole output, is in the log. */
  private static String reason(RuntimeException e) {
    if (e.getCause() instanceof IOException cause
        && cause.getMessage() != null
        && cause.getMessage().startsWith("ffmpeg failed")) {
      return "ffmpeg couldn't find any audio in this file";
    }
    return "see the server log for details";
  }

  private Path saveTranscript(Path audio, String text) throws IOException {
    Path dir = fileStorageSettings.trsDir();
    Files.createDirectories(dir); // does nothing if it already exists
    String name = audio.getFileName().toString();
    int dot = name.lastIndexOf('.');
    Path out = dir.resolve((dot > 0 ? name.substring(0, dot) : name) + ".txt");
    Files.writeString(out, text); // UTF-8, creates the file
    return out;
  }
}
