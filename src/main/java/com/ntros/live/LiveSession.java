package com.ntros.live;

import com.ntros.data.Wav;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * One live stream. Audio arrives on Jetty's threads, so only the audio buffer is locked. Everything
 * else is read and changed on the live-1 thread only.
 */
final class LiveSession {

  final String streamId;
  final LiveClient client;
  /** Completes when the stream is finished: true if live confirmed any text. */
  final CompletableFuture<Boolean> done = new CompletableFuture<>();

  // all audio of the stream, also saved as WAV at the end
  private byte[] audio = new byte[Wav.BYTES_PER_SECOND * 60];
  private int size;

  // only touched on the live-1 thread
  int windowStart; // byte offset where the not-yet-confirmed audio begins
  int lastTickEnd; // size at the last transcription, to skip when little is new
  final StringBuilder confirmed = new StringBuilder();
  String lastPartial = "";
  boolean clientGone; // the socket dropped: keep transcribing, stop sending

  LiveSession(String streamId, LiveClient client) {
    this.streamId = streamId;
    this.client = client;
  }

  synchronized void append(ByteBuffer data) {
    int n = data.remaining();
    if (size + n > audio.length) {
      audio = Arrays.copyOf(audio, Math.max(audio.length * 2, size + n));
    }
    data.get(audio, size, n); // never .array(): the buffer may be a slice of a bigger one
    size += n;
  }

  synchronized int size() {
    return size;
  }

  synchronized byte[] copy(int from, int to) {
    return Arrays.copyOfRange(audio, from, to);
  }
}
