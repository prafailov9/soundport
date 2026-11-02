package com.ntros.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.ntros.live.TestAudio.concat;
import static com.ntros.live.TestAudio.silence;
import static com.ntros.live.TestAudio.speech;
import static com.ntros.live.TestAudio.tone;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SpeechDetectorTest {

  private static double[] levels(byte[]... parts) {
    return SpeechDetector.frameLevels(concat(parts));
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
