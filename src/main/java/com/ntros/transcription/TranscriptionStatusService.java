package com.ntros.transcription;

import com.ntros.data.Job;
import com.ntros.data.State;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The file-upload jobs, so the page can ask how its upload is doing. The server creates a job and
 * reads it; the worker moves it along. A Job is immutable and replaced on every change, so a reader
 * never sees a half-updated one.
 */
public class TranscriptionStatusService {

  private final Map<String, Job> jobs = new ConcurrentHashMap<>();

  /** A new queued job. The id is a UUID: whoever knows it can read the transcript. */
  public String create() {
    String id = UUID.randomUUID().toString();
    jobs.put(id, new Job(State.QUEUED, null, null, null));
    return id;
  }

  public void running(String id) {
    jobs.put(id, new Job(State.RUNNING, null, null, null));
  }

  /** @param file the saved transcript, or null if saving it failed */
  public void done(String id, String text, Path file) {
    jobs.put(id, new Job(State.DONE, text, file, null));
  }

  public void failed(String id, String error) {
    jobs.put(id, new Job(State.FAILED, null, null, error));
  }

  public Optional<Job> get(String id) {
    return Optional.ofNullable(id == null ? null : jobs.get(id));
  }
}
