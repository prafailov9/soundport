package com.ntros.output;

/**
 * One piece of text for {@link WinScreenWriter}. The producer decides how it is delivered, so the
 * writer does not need to know whether it came from an upload or a live stream.
 *
 * @param text what to type into the focused window
 * @param clipboardText what to put on the clipboard if typing isn't possible
 * @param waitForWindow wait a few seconds for an allowed window, or decide right away
 */
public record TextOut(String text, String clipboardText, boolean waitForWindow) {

  public static TextOut upload(String text) {
    return new TextOut(text, text, true);
  }

  /**
   * A confirmed live segment. Live segments arrive every few seconds, so waiting for a window would
   * back up the queue, and the clipboard gets the whole session so the last sentence doesn't
   * replace the earlier ones.
   *
   * @param typed the segment, with a leading space unless it is the first of its stream
   * @param sessionSoFar everything confirmed in this stream, including this segment
   */
  public static TextOut liveSegment(String typed, String sessionSoFar) {
    return new TextOut(typed, sessionSoFar, false);
  }
}
