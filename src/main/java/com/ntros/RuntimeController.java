package com.ntros;

import com.ntros.data.FileStorageSettings;
import com.ntros.data.ServerSettings;
import com.ntros.fileprocessor.AudioFileProcessor;
import com.ntros.fileprocessor.FileDispatchingFunction;
import com.ntros.fileprocessor.FileProcessor;
import com.ntros.server.JavalinWebServer;
import com.ntros.transcription.WhisperServerProcess;
import com.ntros.transcription.WhisperServerTranscriber;
import com.ntros.watcher.FileWatcher;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RuntimeController implements LifeCycle {

  private final FileWatcher watcher;
  private final WhisperServerProcess process;
  private final JavalinWebServer javalinWebServer;

  public RuntimeController(ServerSettings serverSettings, FileStorageSettings fileStorageSettings) {
    if (serverSettings == null) {
      throw new IllegalArgumentException("Empty Server Settings");
    }
    if (fileStorageSettings == null) {
      throw new IllegalArgumentException("Empty FileStorageSettings Settings");
    }

    FileProcessor fileProcessor = new AudioFileProcessor(fileStorageSettings);
    BlockingQueue<Path> fileQueue = new LinkedBlockingQueue<>();
    process =
        new WhisperServerProcess(
            Path.of("C:\\tools\\whisper-cuda\\Release\\whisper-server.exe"),
            Path.of("C:\\tools\\whisper-bin-x64\\models\\ggml-large-v3-turbo-q5_0.bin"),
            8080,
            Path.of("whisper-server.log"));

    var transcriber = new WhisperServerTranscriber(process.inferenceUri());
    watcher = new FileWatcher(fileQueue, transcriber);

    javalinWebServer =
        new JavalinWebServer(
            serverSettings,
            fileStorageSettings,
            (req) -> {
              var res = fileProcessor.write(req);
              if (!res.isSuccess()) {
                // skip dispatch, return failure
                return false;
              }
              // try dispatch
              return fileQueue.offer(res.getCreatedFile());
            });
  }

  /**
   * process runs the model. It needs to start first. Then the middleware. Last is the server
   */
  @Override
  public void start() {
    process.start();
    watcher.start();
    javalinWebServer.start();
  }

  /**
   * shutdown in reverse order
   * @throws InterruptedException
   */
  @Override
  public void stop() throws InterruptedException {
    javalinWebServer.stop();
    watcher.stop();
    process.stop();
  }
}
