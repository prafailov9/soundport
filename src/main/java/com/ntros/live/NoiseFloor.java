package com.ntros.live;

import java.util.Arrays;

/**
 * The background noise of one stream, so speech is judged against the room instead of a fixed
 * level. A faint breath or click in a quiet room stays below the bar; a quiet speaker in a quiet
 * room still clears it.
 */
final class NoiseFloor {

  static final double QUIETEST_SPEECH_DBFS = -45.0; // the bar never goes below this
  static final double LOUDEST_BAR_DBFS = -35.0; // nor above this, or normal speech would miss it
  static final double SPEECH_ABOVE_NOISE_DB = 15.0;

  private static final int HISTORY_FRAMES = 300; // the last 30 s, so the bar follows the room
  private static final double NOISE_PERCENTILE = 0.2; // most streams are quiet a fifth of the time

  private final double[] history = new double[HISTORY_FRAMES];
  private int count; // frames seen, up to HISTORY_FRAMES
  private int next; // where the next frame goes; the oldest is overwritten

  void add(double[] levels) {
    for (double level : levels) {
      history[next] = level;
      next = (next + 1) % HISTORY_FRAMES;
      count = Math.min(count + 1, HISTORY_FRAMES);
    }
  }

  /** Louder than this counts as speech. */
  double speechThreshold() {
    if (count == 0) {
      return LOUDEST_BAR_DBFS;
    }
    double[] sorted = Arrays.copyOf(history, count);
    Arrays.sort(sorted);
    double noise = sorted[(int) (count * NOISE_PERCENTILE)];
    return Math.clamp(noise + SPEECH_ABOVE_NOISE_DB, QUIETEST_SPEECH_DBFS, LOUDEST_BAR_DBFS);
  }
}
