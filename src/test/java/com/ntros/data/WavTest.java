package com.ntros.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WavTest {

  @Test
  void toWavPutsHeaderBeforeSamples() {
    byte[] pcm = {1, 2, 3, 4};

    byte[] wav = Wav.toWav(pcm);

    assertEquals(44 + pcm.length, wav.length);
    assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
    assertArrayEquals(Wav.header(pcm.length), Arrays.copyOf(wav, 44));
    assertArrayEquals(pcm, Arrays.copyOfRange(wav, 44, wav.length));
  }
}
