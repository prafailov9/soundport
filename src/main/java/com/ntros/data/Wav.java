package com.ntros.data;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wraps raw 16 kHz / mono / 16-bit samples in a WAV header, so any player (and whisper-server)
 * knows how to read them. The header is 44 bytes describing the format; the samples follow as-is.
 */
public final class Wav {

  public static final int SAMPLE_RATE = 16000;
  public static final int BYTES_PER_SAMPLE = 2; // 16-bit
  public static final int BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE;

  private Wav() {}

  public static void write(Path file, byte[] pcm) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      out.write(header(pcm.length));
      out.write(pcm);
    }
  }

  public static byte[] header(int pcmLength) {
    ByteBuffer h =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN); // WAV numbers are little-endian
    h.put(ascii("RIFF"))
        .putInt(36 + pcmLength)
        .put(ascii("WAVE")); // "this is a WAV file of N bytes"
    h.put(ascii("fmt "))
        .putInt(16) // format section, 16 bytes long:
        .putShort((short) 1) //   plain uncompressed samples (PCM)
        .putShort((short) 1) //   1 channel (mono)
        .putInt(SAMPLE_RATE) //   samples per second
        .putInt(BYTES_PER_SECOND) //   bytes per second
        .putShort((short) BYTES_PER_SAMPLE) //   bytes per sample frame
        .putShort((short) 16); //   bits per sample
    h.put(ascii("data")).putInt(pcmLength); // "N bytes of samples follow"
    return h.array();
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }
}
