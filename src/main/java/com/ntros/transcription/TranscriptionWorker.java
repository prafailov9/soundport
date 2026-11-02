package com.ntros.transcription;

import com.ntros.LifeCycle;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranscriptionWorker implements Runnable, LifeCycle {
  private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);

  private final BlockingQueue<Path> fileQueue;
  private final BlockingQueue<String> textQueue;
  private final Thread workerThread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Transcriber att;
  private final Transcriber transcriber;

  public TranscriptionWorker(
      BlockingQueue<Path> fileQueue, BlockingQueue<String> textQueue, Transcriber transcriber) {
    this.fileQueue = fileQueue;
    // TODO: move these strings to settings or a config file
    att =
        new AudioToTextTranscriber(
            Path.of("C:\\tools\\whisper-cuda\\Release\\whisper-cli.exe"), // adjust to where the exe
            // actually is
            Path.of("C:\\tools\\whisper-bin-x64\\models\\ggml-large-v3-turbo-q5_0.bin"));

    this.transcriber = transcriber;
    this.textQueue = textQueue;
    workerThread = new Thread(this, "upload-1");
  }

  @Override
  public void run() {
    while (running.get()) {
      try {
        Path f = fileQueue.take();
        // TODO: Add fallback to att

        // TODO: maybe submit to Virtual Threads
        String plaintext = transcriber.transcribe(f);
        // TODO: add command interpretation. If the plaintext contains a password(e.g. a specific
        // sentence), watch for command words after it: cleanup current recordings, write the
        // following text in files, etc.
        log.info("Transcribed:\n{}", plaintext);
        textQueue.offer(plaintext); // the queue has no size limit, so this never refuses
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
