package com.ntros.live;

import com.ntros.data.Wav;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/**
 * Decides what is worth sending to Whisper, from cheap loudness numbers per 100 ms frame: is
 * anything being said, where is the last pause, and where to cut when there is no pause.
 *
 * <p>Whisper invents text ("Thank you.") on silence, so silence must never reach it.
 */
final class SpeechDetector {

  static final int FRAME_BYTES = Wav.BYTES_PER_SECOND / 10; // 100 ms, same as the page's chunks
  static final double SPEECH_DBFS = -45.0; // louder than this = speech. Tune from DEBUG logs
  static final int MIN_SPEECH_FRAMES = 3; // 300 ms of speech before Whisper is worth asking
  static final int MIN_PAUSE_FRAMES = 6; // 600 ms of quiet counts as a pause

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

  static boolean hasSpeech(double[] levels) {
    int loud = 0;
    for (double level : levels) {
      if (level > SPEECH_DBFS) {
        loud++;
      }
    }
    return loud >= MIN_SPEECH_FRAMES;
  }

  /**
   * Where to cut after the last pause that follows speech: 300 ms into the quiet stretch. Works for
   * a pause in the middle and for trailing silence (you stopped talking). Quiet before any speech
   * is not a pause.
   *
   * @return the frame index to cut at, or empty if there is no such pause
   */
  static OptionalInt lastPauseCut(double[] levels) {
    OptionalInt cut = OptionalInt.empty();
    boolean spokeBefore = false;
    int quietRun = 0;
    for (int f = 0; f < levels.length; f++) {
      if (levels[f] > SPEECH_DBFS) {
        spokeBefore = true;
        quietRun = 0;
      } else if (spokeBefore && ++quietRun == MIN_PAUSE_FRAMES) {
        int pauseStart = f - MIN_PAUSE_FRAMES + 1;
        cut = OptionalInt.of(pauseStart + MIN_PAUSE_FRAMES / 2);
      }
    }
    return cut;
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
