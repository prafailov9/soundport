package com.ntros.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ntros.data.Job;
import com.ntros.data.State;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TranscriptionStatusServiceTest {

  private final TranscriptionStatusService jobs = new TranscriptionStatusService();

  @Test
  void newJobIsQueued() {
    String id = jobs.create();

    assertEquals(new Job(State.QUEUED, null, null, null), jobs.get(id).orElseThrow());
    assertNotEquals(id, jobs.create());
  }

  @Test
  void doneKeepsTheTextAndTheFile() {
    String id = jobs.create();
    jobs.running(id);
    assertEquals(State.RUNNING, jobs.get(id).orElseThrow().state());

    Path file = Path.of("transcripts/ready/a.txt");
    jobs.done(id, "Hello.", file);

    assertEquals(new Job(State.DONE, "Hello.", file, null), jobs.get(id).orElseThrow());
  }

  @Test
  void failedKeepsTheError() {
    String id = jobs.create();
    jobs.failed(id, "ffmpeg failed");

    assertEquals(new Job(State.FAILED, null, null, "ffmpeg failed"), jobs.get(id).orElseThrow());
  }

  @Test
  void unknownIdIsEmpty() {
    assertTrue(jobs.get("nope").isEmpty());
    assertTrue(jobs.get(null).isEmpty());
  }
}
