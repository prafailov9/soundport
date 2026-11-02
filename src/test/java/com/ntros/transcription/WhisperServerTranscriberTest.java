package com.ntros.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WhisperServerTranscriberTest {

  @Test
  void stripsWhisperMarkers() {
    assertEquals("", WhisperServerTranscriber.stripMarkers("[BLANK_AUDIO]").strip());
    assertEquals(
        "Hello there. Bye.",
        WhisperServerTranscriber.stripMarkers("Hello there. [Music] Bye. (silence)").strip());
  }

  @Test
  void keepsLineBreaks() {
    assertEquals(
        "First line.\nSecond line.",
        WhisperServerTranscriber.stripMarkers("First line.\nSecond line."));
  }
}
