package com.ntros.live;

import com.ntros.data.Wav;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/**
 * Decides what is worth sending to Whisper, from cheap loudness numbers per 100 ms frame: is
 * anything being said, where is the last pause, and where to cut when there is no pause. Frames
 * louder than the caller's threshold (see {@link NoiseFloor}) count as speech.
 *
 * <p>Whisper invents text ("Thank you.") on silence and repeats itself on very short clips, so
 * silence must never reach it, and a lone word waits for the rest of its sentence.
 */
final class SpeechDetector {

  static final int FRAME_BYTES = Wav.BYTES_PER_SECOND / 10; // 100 ms, same as the page's chunks
  static final int MIN_SPEECH_FRAMES = 3; // 300 ms of speech before Whisper is worth asking
  static final int MIN_PAUSE_FRAMES = 6; // 600 ms of quiet counts as a pause
  static final int MIN_SEGMENT_SPEECH_FRAMES = 15; // a pause after less speech than 1.5 s waits
  static final int QUIET_MARGIN_FRAMES = 5; // quiet kept around speech when squeezing

  private static final int SAMPLES_PER_FRAME = FRAME_BYTES / Wav.BYTES_PER_SAMPLE;

  private SpeechDetector() {}

  /**
   * Loudness of each whole 100 ms frame, in dBFS: 0 is the loudest possible, -90 is digital
   * silence. A trailing partial frame is ignored.
   */
  static double[] frameLevels(byte[] pcm) {
    double[] levels = new double[pcm.length / FRAME_BYTES];
    ByteBuffer buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    for (int f = 0; f < levels.length; f++) {
      double sum = 0;
      for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
        double s = buf.getShort() / 32768.0;
        sum += s * s;
      }
      double rms = Math.sqrt(sum / SAMPLES_PER_FRAME);
      levels[f] = 20 * Math.log10(rms + 1e-9);
    }
    return levels;
  }

  static boolean hasSpeech(double[] levels, double threshold) {
    int loud = 0;
    for (double level : levels) {
      if (level > threshold) {
        loud++;
      }
    }
    return loud >= MIN_SPEECH_FRAMES;
  }

  /**
   * Where to cut after the last pause that follows at least 1.5 s of speech: 300 ms into the quiet
   * stretch. Works for a pause in the middle and for trailing silence (you stopped talking). Quiet
   * before any speech is not a pause, and a pause after a lone word is skipped: cut out on its own,
   * Whisper gets it wrong or repeats it.
   *
   * @return the frame index to cut at, or empty if there is no such pause
   */
  static OptionalInt lastPauseCut(double[] levels, double threshold) {
    OptionalInt cut = OptionalInt.empty();
    int spoken = 0; // speech frames so far
    int quietRun = 0;
    for (int f = 0; f < levels.length; f++) {
      if (levels[f] > threshold) {
        spoken++;
        quietRun = 0;
      } else if (spoken > 0
          && ++quietRun == MIN_PAUSE_FRAMES
          && spoken >= MIN_SEGMENT_SPEECH_FRAMES) {
        int pauseStart = f - MIN_PAUSE_FRAMES + 1;
        cut = OptionalInt.of(pauseStart + MIN_PAUSE_FRAMES / 2);
      }
    }
    return cut;
  }

  /**
   * The audio worth sending to Whisper: speech with up to 500 ms of quiet on each side. Longer
   * quiet stretches, before, between or after speech, are left out. Replayed recordings showed
   * this keeps Whisper from inventing text in them, and 500 ms is enough to keep sentences apart.
   * A trailing partial frame is kept if the last whole frame is.
   */
  static byte[] squeeze(byte[] pcm, double[] levels, double threshold) {
    boolean[] keep = new boolean[levels.length];
    for (int f = 0; f < levels.length; f++) {
      if (levels[f] > threshold) {
        int to = Math.min(levels.length - 1, f + QUIET_MARGIN_FRAMES);
        for (int g = Math.max(0, f - QUIET_MARGIN_FRAMES); g <= to; g++) {
          keep[g] = true;
        }
      }
    }
    var out = new ByteArrayOutputStream(pcm.length);
    for (int f = 0; f < levels.length; f++) {
      if (keep[f]) {
        out.write(pcm, f * FRAME_BYTES, FRAME_BYTES);
      }
    }
    int whole = levels.length * FRAME_BYTES;
    if (levels.length > 0 && keep[levels.length - 1]) {
      out.write(pcm, whole, pcm.length - whole);
    }
    return out.toByteArray();
  }

  /** The quietest frame at or after fromFrame: the forced cut when a window gets too long. */
  static int quietestFrame(double[] levels, int fromFrame) {
    int quietest = Math.max(0, fromFrame);
    for (int f = quietest + 1; f < levels.length; f++) {
      if (levels[f] < levels[quietest]) {
        quietest = f;
      }
    }
    return quietest;
  }
}
