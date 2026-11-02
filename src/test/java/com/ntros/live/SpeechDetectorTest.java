package com.ntros.live;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

  private static final double SPEECH = -45.0;

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
    assertFalse(SpeechDetector.hasSpeech(levels(silence(2)), SPEECH));
    assertFalse(SpeechDetector.hasSpeech(levels(speech(0.2), silence(1)), SPEECH));
    assertTrue(SpeechDetector.hasSpeech(levels(silence(1), speech(0.3)), SPEECH));
  }

  @Test
  void hasSpeechIgnoresQuietNoise() {
    assertFalse(SpeechDetector.hasSpeech(levels(tone(2, 0.001)), SPEECH)); // about -63 dBFS
  }

  @Test
  void pauseInTheMiddleIsCutThreeHundredMsIn() {
    OptionalInt cut = SpeechDetector.lastPauseCut(levels(speech(2), silence(1), speech(1)), SPEECH);

    assertEquals(OptionalInt.of(23), cut); // pause starts at frame 20
  }

  @Test
  void trailingSilenceCountsAsPause() {
    assertEquals(
        OptionalInt.of(23), SpeechDetector.lastPauseCut(levels(speech(2), silence(0.6)), SPEECH));
  }

  @Test
  void shortGapIsNotAPause() {
    assertTrue(SpeechDetector.lastPauseCut(levels(speech(2), silence(0.5)), SPEECH).isEmpty());
    assertTrue(
        SpeechDetector.lastPauseCut(levels(speech(2), silence(0.5), speech(1)), SPEECH).isEmpty());
  }

  @Test
  void silenceBeforeSpeechIsNotAPause() {
    assertTrue(SpeechDetector.lastPauseCut(levels(silence(2), speech(2)), SPEECH).isEmpty());
  }

  @Test
  void pauseAfterALoneWordWaitsForMoreSpeech() {
    assertTrue(SpeechDetector.lastPauseCut(levels(speech(0.5), silence(1)), SPEECH).isEmpty());

    // the word and the sentence after it are cut together
    OptionalInt cut =
        SpeechDetector.lastPauseCut(
            levels(speech(0.5), silence(1), speech(1), silence(1)), SPEECH);
    assertEquals(OptionalInt.of(28), cut); // second pause starts at frame 25
  }

  @Test
  void lastOfSeveralPausesWins() {
    OptionalInt cut =
        SpeechDetector.lastPauseCut(
            levels(speech(2), silence(1), speech(2), silence(1), speech(0.5)), SPEECH);

    assertEquals(OptionalInt.of(53), cut); // second pause starts at frame 50
  }

  @Test
  void squeezeKeepsHalfASecondOfQuietAroundSpeech() {
    byte[] pcm = concat(silence(3), speech(1), silence(4), speech(1), silence(0.3));

    byte[] sent = SpeechDetector.squeeze(pcm, SpeechDetector.frameLevels(pcm), SPEECH);

    // 0.5 + 1 + 0.5 | 0.5 + 1 + 0.3 (the trailing quiet is shorter than the margin)
    assertArrayEquals(
        concat(silence(0.5), speech(1), silence(0.5), silence(0.5), speech(1), silence(0.3)),
        sent);
  }

  @Test
  void squeezeKeepsATrailingPartialFrameAfterSpeech() {
    byte[] pcm = concat(speech(1), silence(0.05));

    byte[] sent = SpeechDetector.squeeze(pcm, SpeechDetector.frameLevels(pcm), SPEECH);

    assertEquals(pcm.length, sent.length);
  }

  @Test
  void quietestFrameSearchesFromTheGivenFrame() {
    double[] levels = {-90, -20, -30, -50, -25, -40};

    assertEquals(3, SpeechDetector.quietestFrame(levels, 1));
    assertEquals(5, SpeechDetector.quietestFrame(levels, 4));
    assertEquals(0, SpeechDetector.quietestFrame(levels, -3));
  }
}
