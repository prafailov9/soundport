package com.ntros.live;

import static com.ntros.live.TestAudio.concat;
import static com.ntros.live.TestAudio.silence;
import static com.ntros.live.TestAudio.speech;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ntros.data.FileStorageSettings;
import com.ntros.data.Wav;
import com.ntros.output.TextOut;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveTranscriptionServiceTest {

  private static final String ID = "stream-1";
  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String DONE = "{\"done\":true}";

  /** Answers seg1, seg2, ... and remembers how many bytes each call got. */
  private static final class FakeTranscriber implements LiveTranscriber {
    final List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
    boolean failFirst;

    @Override
    public String transcribe(byte[] pcm) {
      calls.add(pcm.length);
      if (failFirst && calls.size() == 1) {
        throw new RuntimeException("whisper-server down");
      }
      return "seg" + calls.size();
    }
  }

  private static final class FakeClient implements LiveClient {
    final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    volatile boolean closed;

    @Override
    public void send(String json) {
      messages.add(json);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  @TempDir Path tmp;

  private final FakeTranscriber transcriber = new FakeTranscriber();
  private final FakeClient client = new FakeClient();
  private final LinkedBlockingQueue<TextOut> textQueue = new LinkedBlockingQueue<>();
  private LiveTranscriptionService service;

  @BeforeEach
  void setUp() {
    var files =
        new FileStorageSettings(
            tmp,
            tmp.resolve("tmp"),
            tmp.resolve("ready"),
            tmp.resolve("live"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
    // not started: the tests call tick() themselves instead of waiting for the timer
    service = new LiveTranscriptionService(transcriber, textQueue, files);
    service.open(ID, client);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    service.stop();
  }

  private void stream(byte[]... parts) {
    service.audio(client, ByteBuffer.wrap(concat(parts)));
  }

  private static int bytes(double seconds) {
    return (int) Math.round(seconds * Wav.BYTES_PER_SECOND);
  }

  @Test
  void silenceIsNeverSentToWhisper() throws IOException {
    stream(silence(2));
    service.tick();
    service.end(client);

    assertFalse(service.coveredByLive(ID, WAIT));
    assertTrue(transcriber.calls.isEmpty());
    assertEquals(List.of(DONE), client.messages);
    assertTrue(client.closed);
    try (Stream<Path> saved = Files.list(tmp.resolve("live"))) {
      assertEquals(1, saved.count());
    }
  }

  @Test
  void pauseConfirmsTheTextBeforeIt() {
    stream(speech(2), silence(1));
    service.tick();

    assertEquals(List.of(bytes(2.3)), transcriber.calls); // cut 300 ms into the pause
    assertEquals(List.of("{\"final\":\"seg1\"}"), client.messages);
    assertEquals(new TextOut("seg1", "seg1", false), textQueue.poll());

    service.end(client);
    assertTrue(service.coveredByLive(ID, WAIT));
    assertEquals(1, transcriber.calls.size()); // only silence was left
  }

  @Test
  void ongoingSpeechIsOnlyTentativeUntilTheEnd() {
    stream(speech(2));
    service.tick();

    assertEquals(List.of("{\"partial\":\"seg1\"}"), client.messages);
    assertTrue(textQueue.isEmpty());

    service.end(client);
    assertTrue(service.coveredByLive(ID, WAIT));
    assertEquals(
        List.of("{\"partial\":\"seg1\"}", "{\"final\":\"seg2\"}", "{\"partial\":\"\"}", DONE),
        client.messages);
    assertEquals(new TextOut("seg2", "seg2", false), textQueue.poll());
  }

  @Test
  void laterSegmentsGetASpaceAndTheClipboardGetsTheWholeSession() {
    stream(speech(2), silence(1));
    service.tick();
    stream(speech(2), silence(1));
    service.tick();

    assertEquals(new TextOut("seg1", "seg1", false), textQueue.poll());
    assertEquals(new TextOut(" seg2", "seg1 seg2", false), textQueue.poll());
  }

  @Test
  void loneWordWaitsForTheRestOfItsSentence() {
    stream(speech(0.5), silence(1));
    service.tick();

    assertEquals(List.of("{\"partial\":\"seg1\"}"), client.messages); // tentative only
    assertTrue(textQueue.isEmpty());

    stream(speech(2), silence(1));
    service.tick();

    // the word, 0.5 s of its pause, then the sentence: confirmed together
    assertEquals(bytes(0.5 + 0.5 + 0.5 + 2 + 0.3), transcriber.calls.get(1));
    assertEquals(new TextOut("seg2", "seg2", false), textQueue.poll());
  }

  @Test
  void longQuietBeforeSpeechIsNotSentToWhisper() {
    stream(silence(0.4), speech(0.4), silence(3), speech(2)); // a word, a long gap, a sentence
    service.end(client);

    assertTrue(service.coveredByLive(ID, WAIT));
    // the first word with 0.4 s before it, 0.5 s on each side of the 3 s gap, then the speech
    assertEquals(List.of(bytes(0.4 + 0.4 + 0.5 + 0.5 + 2)), transcriber.calls);
  }

  @Test
  void waitsForHalfASecondOfNewAudio() {
    stream(speech(0.4));
    service.tick();

    assertTrue(transcriber.calls.isEmpty());
  }

  @Test
  void longSpeechWithoutAPauseIsCutInTheLastFiveSeconds() {
    stream(speech(21));
    service.tick();

    // every frame is equally loud, so the first frame of the last 5 s wins
    assertEquals(List.of(bytes(16), bytes(5)), transcriber.calls);
    assertEquals(
        List.of("{\"final\":\"seg1\"}", "{\"partial\":\"seg2\"}"), client.messages);
  }

  @Test
  void droppedStreamStillTypesButSendsNothing() {
    stream(speech(1));
    service.dropped(client);

    assertTrue(service.coveredByLive(ID, WAIT));
    assertTrue(client.messages.isEmpty());
    assertFalse(client.closed);
    assertEquals(new TextOut("seg1", "seg1", false), textQueue.poll());
  }

  @Test
  void endFollowedByCloseFinishesOnce() {
    stream(speech(1));
    service.end(client);
    service.dropped(client);

    assertTrue(service.coveredByLive(ID, WAIT));
    assertEquals(1, transcriber.calls.size());
    assertEquals(1, client.messages.stream().filter(DONE::equals).count());
  }

  @Test
  void unknownStreamIsNotCovered() {
    assertFalse(service.coveredByLive("never-streamed", WAIT));
    assertFalse(service.coveredByLive(null, WAIT));
  }

  @Test
  void secondStreamWithTheSameIdIsRefusedAndLeavesTheFirstAlone() {
    var second = new FakeClient();
    service.open(ID, second);
    service.audio(second, ByteBuffer.wrap(speech(2)));
    service.dropped(second);

    assertTrue(second.closed);
    assertFalse(client.closed);

    stream(speech(1));
    service.tick();
    assertEquals(List.of(bytes(1)), transcriber.calls); // only the first stream's audio
  }

  @Test
  void whisperFailureDoesNotStopTheStream() {
    transcriber.failFirst = true;
    stream(speech(2));
    assertDoesNotThrow(service::tick);

    stream(speech(1));
    service.tick();

    assertEquals(List.of("{\"partial\":\"seg2\"}"), client.messages);
  }
}
