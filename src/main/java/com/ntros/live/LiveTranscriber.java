package com.ntros.live;

/** Turns raw 16 kHz mono 16-bit PCM into a single line of text. */
@FunctionalInterface
public interface LiveTranscriber {

  String transcribe(byte[] pcm);
}
