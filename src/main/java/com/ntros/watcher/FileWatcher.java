package com.ntros.watcher;

import com.ntros.LifeCycle;
import com.ntros.transcription.AudioToTextTranscriber;
import com.ntros.transcription.Transcriber;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileWatcher implements Runnable, LifeCycle {
  private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

  private final BlockingQueue<Path> fileQueue;
  private final Thread watcherThread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Transcriber att;
  private final Transcriber transcriber;

  public FileWatcher(BlockingQueue<Path> fileQueue, Transcriber transcriber) {
    this.fileQueue = fileQueue;
    // TODO: move these strings to settings or a config file
    att =
        new AudioToTextTranscriber(
            Path.of("C:\\tools\\whisper-cuda\\Release\\whisper-cli.exe"), // adjust to where the exe
            // actually is
            Path.of("C:\\tools\\whisper-bin-x64\\models\\ggml-large-v3-turbo-q5_0.bin"));

    this.transcriber = transcriber;
    watcherThread = new Thread(this, "watcher-1");
  }

  @Override
  public void run() {
    while (running.get()) {
      try {
        Path f = fileQueue.take(); // sleeps until a file arrives
        // TODO: Add fallback to att
        String plaintext = transcriber.transcribe(f);
        log.info("Transcribed:\n{}", plaintext);

        // automated Ctrl+C
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(plaintext), null);
      } catch (InterruptedException e) {
        break;
      } catch (RuntimeException e) {
        log.error("Transcription failed for this file", e);
      }
    }
  }

  @Override
  public void start() {
    watcherThread.start();
  }

  @Override
  public void stop() throws InterruptedException {
    running.set(false);
    watcherThread.interrupt(); // wakes take() so the loop can exit
    watcherThread.join();
  }
}
