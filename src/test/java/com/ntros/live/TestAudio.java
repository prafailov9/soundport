package com.ntros.live;

import com.ntros.data.Wav;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Made-up 16 kHz mono 16-bit audio: a 440 Hz tone stands in for speech. */
final class TestAudio {

  private TestAudio() {}

  static byte[] tone(double seconds, double amplitude) {
    int samples = (int) Math.round(seconds * Wav.SAMPLE_RATE);
    ByteBuffer buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) {
      double s = amplitude * Math.sin(2 * Math.PI * 440 * i / Wav.SAMPLE_RATE);
      buf.putShort((short) Math.round(s * 32767));
    }
    return buf.array();
  }

  /** About -9 dBFS. */
  static byte[] speech(double seconds) {
    return tone(seconds, 0.5);
  }

  static byte[] silence(double seconds) {
    return new byte[(int) Math.round(seconds * Wav.SAMPLE_RATE) * 2];
  }

  static byte[] concat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }
}
