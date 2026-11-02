package com.ntros.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ntros.data.Wav;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SpeechDetectorTest {

  // a 440 Hz tone at half volume stands in for speech: about -9 dBFS
  private static byte[] tone(double seconds, double amplitude) {
    int samples = (int) Math.round(seconds * Wav.SAMPLE_RATE);
    ByteBuffer buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) {
      double s = amplitude * Math.sin(2 * Math.PI * 440 * i / Wav.SAMPLE_RATE);
      buf.putShort((short) Math.round(s * 32767));
    }
    return buf.array();
  }

  private static byte[] speech(double seconds) {
    return tone(seconds, 0.5);
  }

  private static byte[] silence(double seconds) {
    return new byte[(int) Math.round(seconds * Wav.SAMPLE_RATE) * 2];
  }

  private static double[] levels(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return SpeechDetector.frameLevels(out.toByteArray());
  }

  @Test
  void frameLevelsMeasureLoudnessPerFrame() {
    double[] levels = levels(speech(0.3), silence(0.2));

    assertEquals(5, levels.length);
    assertEquals(-9.03, levels[0], 0.1); // sine RMS = amplitude / sqrt(2)
    assertTrue(levels[4] < -80);
  }

  @Test
  void frameLevelsIgnoreTrailingPartialFrame() {
    assertEquals(2, SpeechDetector.frameLevels(silence(0.25)).length);
  }

  @Test
  void hasSpeechNeedsEnoughLoudFrames() {
    assertFalse(SpeechDetector.hasSpeech(levels(silence(2))));
    assertFalse(SpeechDetector.hasSpeech(levels(speech(0.2), silence(1))));
    assertTrue(SpeechDetector.hasSpeech(levels(silence(1), speech(0.3))));
  }

  @Test
  void hasSpeechIgnoresQuietNoise() {
    assertFalse(SpeechDetector.hasSpeech(levels(tone(2, 0.001)))); // about -63 dBFS
  }

  @Test
  void pauseInTheMiddleIsCutThreeHundredMsIn() {
    OptionalInt cut = SpeechDetector.lastPauseCut(levels(speech(1), silence(1), speech(1)));

    assertEquals(OptionalInt.of(13), cut); // pause starts at frame 10
  }

  @Test
  void trailingSilenceCountsAsPause() {
    assertEquals(
        OptionalInt.of(13), SpeechDetector.lastPauseCut(levels(speech(1), silence(0.6))));
  }

  @Test
  void shortGapIsNotAPause() {
    assertTrue(SpeechDetector.lastPauseCut(levels(speech(1), silence(0.5))).isEmpty());
    assertTrue(
        SpeechDetector.lastPauseCut(levels(speech(1), silence(0.5), speech(1))).isEmpty());
  }

  @Test
  void silenceBeforeSpeechIsNotAPause() {
    assertTrue(SpeechDetector.lastPauseCut(levels(silence(2), speech(1))).isEmpty());
  }

  @Test
  void lastOfSeveralPausesWins() {
    OptionalInt cut =
        SpeechDetector.lastPauseCut(
            levels(speech(1), silence(1), speech(1), silence(1), speech(0.5)));

    assertEquals(OptionalInt.of(33), cut); // second pause starts at frame 30
  }

  @Test
  void quietestFrameSearchesFromTheGivenFrame() {
    double[] levels = {-90, -20, -30, -50, -25, -40};

    assertEquals(3, SpeechDetector.quietestFrame(levels, 1));
    assertEquals(5, SpeechDetector.quietestFrame(levels, 4));
    assertEquals(0, SpeechDetector.quietestFrame(levels, -3));
  }
}
