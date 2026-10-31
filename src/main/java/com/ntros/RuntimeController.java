package com.ntros;

import com.ntros.data.FileStorageSettings;
import com.ntros.data.ServerSettings;
import com.ntros.fileprocessor.AudioFileProcessor;
import com.ntros.fileprocessor.FileProcessor;
import com.ntros.server.JavalinWebServer;
import com.ntros.transcription.WinScreenWriter;
import com.ntros.transcription.WhisperServerProcess;
import com.ntros.transcription.WhisperServerTranscriber;
import com.ntros.transcription.TranscriptionWorker;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class RuntimeController implements LifeCycle {

  private final TranscriptionWorker transcriptionWorker;
  private final WhisperServerProcess process;
  private final WinScreenWriter winScreenWriter;
  private final JavalinWebServer javalinWebServer;

  public RuntimeController(ServerSettings serverSettings, FileStorageSettings fileStorageSettings) {
    if (serverSettings == null) {
      throw new IllegalArgumentException("Empty Server Settings");
    }
    if (fileStorageSettings == null) {
      throw new IllegalArgumentException("Empty FileStorageSettings Settings");
    }

    FileProcessor fileProcessor = new AudioFileProcessor(fileStorageSettings);
    // audio2text queue
    BlockingQueue<Path> fileQueue = new LinkedBlockingQueue<>();
    // text2screen queue
    BlockingQueue<String> textQueue = new LinkedBlockingQueue<>();
    winScreenWriter = new WinScreenWriter(textQueue);

    process =
        new WhisperServerProcess(
            Path.of("C:\\tools\\whisper-cuda\\Release\\whisper-server.exe"),
            Path.of("C:\\tools\\whisper-bin-x64\\models\\ggml-large-v3-turbo-q5_0.bin"),
            8080,
            Path.of("whisper-server.log"));

    var transcriber = new WhisperServerTranscriber(process.inferenceUri());
    transcriptionWorker = new TranscriptionWorker(fileQueue, textQueue, transcriber);

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

  /** process runs the model. It needs to start first. Then the middleware. Last is the server */
  @Override
  public void start() {
    process.start();
    transcriptionWorker.start();
    javalinWebServer.start();
    winScreenWriter.start();
  }

  /**
   * shutdown in reverse order
   *
   * @throws InterruptedException
   */
  @Override
  public void stop() throws InterruptedException {
    winScreenWriter.stop();
    javalinWebServer.stop();
    transcriptionWorker.stop();
    process.stop();
  }
}
