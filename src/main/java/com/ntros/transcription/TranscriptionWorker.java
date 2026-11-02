package com.ntros.transcription;

import com.ntros.LifeCycle;
import com.ntros.data.UploadJob;
import com.ntros.output.TextOut;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranscriptionWorker implements Runnable, LifeCycle {
  private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

  private final BlockingQueue<UploadJob> uploadQueue;
  private final BlockingQueue<TextOut> textQueue;
  // true if the live stream with this id already typed the text; the upload is then a duplicate
  private final Predicate<String> coveredByLive;
  private final Thread workerThread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Transcriber att;
  private final Transcriber transcriber;

  public TranscriptionWorker(
      BlockingQueue<UploadJob> uploadQueue,
      BlockingQueue<TextOut> textQueue,
      Transcriber transcriber,
      Predicate<String> coveredByLive) {
    this.uploadQueue = uploadQueue;
    // TODO: move these strings to settings or a config file
    att =
        new AudioToTextTranscriber(
            Path.of("C:\\tools\\whisper-cuda\\Release\\whisper-cli.exe"), // adjust to where the exe
            // actually is
            Path.of("C:\\tools\\whisper-bin-x64\\models\\ggml-large-v3-turbo-q5_0.bin"));

    this.transcriber = transcriber;
    this.textQueue = textQueue;
    this.coveredByLive = coveredByLive;
    workerThread = new Thread(this, "upload-1");
  }

  @Override
  public void run() {
    while (running.get()) {
      try {
        UploadJob job = uploadQueue.take();
        if (coveredByLive.test(job.streamId())) {
          log.info("Live already typed {}, skipping the upload", job.file().getFileName());
          continue;
        }
        // TODO: Add fallback to att

        // TODO: maybe submit to Virtual Threads
        String plaintext = transcriber.transcribe(job.file());
        // TODO: add command interpretation later on. If the plaintext contains a password(e.g. a specific
        // sentence), watch for command words after it: cleanup current recordings, write the
        // following text in files, etc.
        log.info("Transcribed:\n{}", plaintext);
        // the queue has no size limit, so this never refuses
        textQueue.offer(TextOut.upload(plaintext));
      } catch (InterruptedException e) {
        break;
      } catch (RuntimeException e) {
        log.error("Transcription failed for this file", e);
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
}
