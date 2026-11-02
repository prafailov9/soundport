package com.ntros.live;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.ntros.LifeCycle;
import com.ntros.data.FileStorageSettings;
import com.ntros.data.Wav;
import com.ntros.output.TextOut;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live transcription of the phone's audio stream. One thread (live-1) wakes about every second and,
 * for each open stream, decides what to send to Whisper, what to confirm and what to send back:
 *
 * <ul>
 *   <li>nothing said: skip it, Whisper invents text on silence
 *   <li>a pause: confirm everything before it, it won't change any more
 *   <li>20 s without a pause: cut at the quietest point of the last 5 s, then the same
 *   <li>the rest is tentative: shown grey on the page, never typed
 * </ul>
 *
 * <p>One thread for all streams, because whisper-server works on one request at a time anyway.
 */
public final class LiveTranscriptionService implements LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(LiveTranscriptionService.class);

  private static final long TICK_MS = 1000;
  private static final int MIN_NEW_BYTES = Wav.BYTES_PER_SECOND / 2; // 0.5 s of new audio
  private static final int MAX_WINDOW_BYTES = Wav.BYTES_PER_SECOND * 20;
  private static final int FORCED_CUT_SEARCH_FRAMES = 50; // the last 5 s
  // on silence, keep the last 0.5 s so a word starting right at the edge isn't cut
  private static final int KEEP_ON_SILENCE = SpeechDetector.FRAME_BYTES * 5;
  private static final String DONE = "{\"done\":true}";

  private final LiveTranscriber transcriber;
  private final BlockingQueue<TextOut> textQueue;
  private final Path liveDir;
  private final DateTimeFormatter formatter;
  private final Map<String, LiveSession> open = new ConcurrentHashMap<>();

  // outlives the session: the upload arrives just after its stream ends
  private final Map<String, CompletableFuture<Boolean>> results = new ConcurrentHashMap<>();
  private final ScheduledExecutorService exec =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "live-1"));

  public LiveTranscriptionService(
      LiveTranscriber transcriber,
      BlockingQueue<TextOut> textQueue,
      FileStorageSettings fileStorageSettings) {
    this.transcriber = transcriber;
    this.textQueue = textQueue;
    this.liveDir = fileStorageSettings.liveDir();
    this.formatter = fileStorageSettings.formatter();
  }

  // ---- called from the web server (Jetty threads) ----

  public void open(String streamId, LiveClient client) {
    var session = new LiveSession(streamId, client);
    if (open.putIfAbsent(streamId, session) != null) {
      log.warn("Live stream {} is already open, refusing the second one", streamId);
      client.close();
      return;
    }
    results.put(streamId, session.done);
    log.info("Live stream started: {}", streamId);
  }

  public void audio(String streamId, ByteBuffer data) {
    var session = open.get(streamId);
    if (session != null) {
      session.append(data);
    }
  }

  /** The page said it's done: transcribe the last sentence, send it, then close the socket. */
  public void end(String streamId) {
    finishLater(streamId, true);
  }

  /** The socket closed. Does nothing if end() already finished this stream. */
  public void dropped(String streamId) {
    finishLater(streamId, false);
  }

  // ---- called from the upload worker ----

  /**
   * Did the live stream with this id produce text? Waits up to {@code wait} for it to finish its
   * last sentence. False for an unknown id: the stream never started, so the upload is needed.
   */
  public boolean coveredByLive(String streamId, Duration wait) {
    var result = streamId == null ? null : results.remove(streamId);
    if (result == null) {
      return false;
    }
    try {
      return result.get(wait.toMillis(), MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      log.warn("Live stream {} didn't finish in time, using the upload", streamId);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void start() {
    exec.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, MILLISECONDS);
  }

  /** Streams that are still being finished get to complete; the ticks stop. */
  @Override
  public void stop() throws InterruptedException {
    exec.shutdown();
    if (!exec.awaitTermination(10, SECONDS)) {
      log.warn("Live transcription didn't finish in time");
    }
  }

  // ---- live-1 thread ----

  void tick() {
    for (LiveSession s : open.values()) {
      try {
        step(s);
      } catch (RuntimeException e) {
        // must not escape: a scheduled task that throws is silently never run again
        log.error("Live step failed for {}", s.streamId, e);
      }
    }
  }

  private void step(LiveSession s) {
    int end = s.size();
    if (end - s.lastTickEnd < MIN_NEW_BYTES) {
      return;
    }
    s.lastTickEnd = end;

    byte[] window = s.copy(s.windowStart, end);
    double[] levels = SpeechDetector.frameLevels(window);
    logLevels(s, levels);

    if (!SpeechDetector.hasSpeech(levels)) {
      s.windowStart = Math.max(s.windowStart, alignToSample(end - KEEP_ON_SILENCE));
      sendPartial(s, "");
      return;
    }

    OptionalInt cut = SpeechDetector.lastPauseCut(levels);
    if (cut.isEmpty() && window.length > MAX_WINDOW_BYTES) {
      cut =
          OptionalInt.of(
              SpeechDetector.quietestFrame(levels, levels.length - FORCED_CUT_SEARCH_FRAMES));
    }
    if (cut.isPresent()) {
      int cutBytes = cut.getAsInt() * SpeechDetector.FRAME_BYTES;
      byte[] head = Arrays.copyOf(window, cutBytes);
      if (SpeechDetector.hasSpeech(SpeechDetector.frameLevels(head))) {
        confirm(s, transcriber.transcribe(head));
      }
      s.windowStart += cutBytes;
      window = Arrays.copyOfRange(window, cutBytes, window.length);
    }

    boolean tailHasSpeech = SpeechDetector.hasSpeech(SpeechDetector.frameLevels(window));
    sendPartial(s, tailHasSpeech ? transcriber.transcribe(window).strip() : "");
  }

  private void finishLater(String streamId, boolean clientWaiting) {
    var session = open.remove(streamId); // whichever of end/dropped comes second finds nothing
    if (session == null) {
      return;
    }
    try {
      exec.execute(() -> finish(session, clientWaiting));
    } catch (RejectedExecutionException e) {
      log.warn("Live stream {} ended during shutdown, not finished", streamId);
      session.done.complete(!session.confirmed.isEmpty());
    }
  }

  private void finish(LiveSession s, boolean clientWaiting) {
    s.clientGone = !clientWaiting;
    try {
      byte[] rest = s.copy(s.windowStart, s.size());
      if (SpeechDetector.hasSpeech(SpeechDetector.frameLevels(rest))) {
        confirm(s, transcriber.transcribe(rest));
      }
    } catch (RuntimeException e) {
      log.error("Transcribing the end of live stream {} failed", s.streamId, e);
    }
    sendPartial(s, "");
    send(s, DONE);
    if (clientWaiting) {
      s.client.close();
    }
    saveWav(s);
    s.done.complete(!s.confirmed.isEmpty());
    log.info("Live stream finished: {}", s.streamId);
  }

  private void confirm(LiveSession s, String text) {
    String clean = text.strip();
    if (clean.isEmpty()) {
      return;
    }
    String typed = s.confirmed.isEmpty() ? clean : " " + clean;
    s.confirmed.append(typed);
    log.info("Live: {}", clean);
    send(s, Json.object("final", clean));
    textQueue.offer(TextOut.liveSegment(typed, s.confirmed.toString()));
  }

  private void sendPartial(LiveSession s, String text) {
    if (text.equals(s.lastPartial)) {
      return; // don't resend the same thing
    }
    s.lastPartial = text;
    send(s, Json.object("partial", text));
  }

  private void send(LiveSession s, String json) {
    if (s.clientGone) {
      return;
    }
    try {
      s.client.send(json);
    } catch (RuntimeException e) {
      s.clientGone = true; // the phone went away mid-stream; keep transcribing for the PC
      log.warn("Live stream {}: can't reach the page any more", s.streamId);
    }
  }

  private void saveWav(LiveSession s) {
    int size = s.size();
    if (size == 0) {
      return;
    }
    try {
      Files.createDirectories(liveDir);
      Path file = liveDir.resolve(LocalDateTime.now().format(formatter) + ".wav");
      Wav.write(file, s.copy(0, size));
      log.info(
          "Live stream saved: {} ({} s)",
          file,
          String.format("%.1f", size / (double) Wav.BYTES_PER_SECOND));
    } catch (IOException e) {
      log.error("Saving live stream {} failed", s.streamId, e);
    }
  }

  private static void logLevels(LiveSession s, double[] levels) {
    if (!log.isDebugEnabled() || levels.length == 0) {
      return;
    }
    double loudest = Arrays.stream(levels).max().orElseThrow();
    double quietest = Arrays.stream(levels).min().orElseThrow();
    log.debug(
        "{}: window {} s, loudest {} dBFS, quietest {} dBFS",
        s.streamId,
        String.format("%.1f", levels.length / 10.0),
        String.format("%.0f", loudest),
        String.format("%.0f", quietest));
  }

  private static int alignToSample(int offset) {
    return Math.max(0, offset) & ~1;
  }
}
