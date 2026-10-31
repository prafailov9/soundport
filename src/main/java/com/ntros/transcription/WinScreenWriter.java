package com.ntros.transcription;

import com.ntros.LifeCycle;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.INPUT;
import com.sun.jna.ptr.IntByReference;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WinScreenWriter implements Runnable, LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(WinScreenWriter.class);

  private static final int WRITE_DELAY_MS = 5_000;
  private static final int KEYEVENTF_KEYUP = 0x0002;
  private static final int KEYEVENTF_UNICODE = 0x0004;
  private static final String INPUT_TYPE_KEYBOARD = "ki";
  private final User32 user32 = User32.INSTANCE;

  private final BlockingQueue<String> textQueue;
  private final Thread screenThread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private static final List<String> PROGRAM_ALLOWLIST =
      List.of("windowsterminal.exe", "claude.exe", "notepad++.exe", "notepad.exe", "obsidian.exe");

  public WinScreenWriter(BlockingQueue<String> textQueue) {
    this.textQueue = textQueue;

    screenThread = new Thread(this, "scr-1");
  }

  @Override
  public void start() {
    screenThread.start();
  }

  @Override
  public void stop() throws InterruptedException {
    running.set(false);
    screenThread.interrupt();
    screenThread.join();
  }

  @Override
  public void run() {
    log.info("ScreenWriter started");
    while (running.get()) {
      String text;
      try {
        text = textQueue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted while reading");
        break;
      }
      if (text.isBlank()) {
        continue;
      }
      // spin for roughly 5 seconds
      tryWriteIn(System.nanoTime() + (WRITE_DELAY_MS * 1_000_000L), text);
    }
  }

  /**
   * Polls the WinOS for an allowed window to write in. If none found in the duration -> Ctrl + C
   *
   * @param untilNanos - time given to find a window to write in
   * @param text - text to copy or write
   */
  private void tryWriteIn(long untilNanos, String text) {
    while (System.nanoTime() < untilNanos) {
      var program = programOf(user32.GetForegroundWindow());
      if (program.isPresent() && PROGRAM_ALLOWLIST.contains(program.get())) {
        write(text);
        return;
      }
      // short delay to allow cpu to breathe
      if (!delay(100)) {
        break;
      }
    }
    copyToClipboard(text);
  }

  /**
   * Writes the text directly in a foreground window's textbox right after already-existing text,
   * without a whitespace. Simulates keyboard input
   *
   * @param text send to windows for writing
   */
  private void write(String text) {
    WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(text.length() * 2);
    int i = 0;
    for (char c : text.toCharArray()) {
      for (boolean up : new boolean[] {false, true}) {
        WinUser.INPUT in = inputs[i++];
        in.type = new DWORD(INPUT.INPUT_KEYBOARD);
        in.input.setType(INPUT_TYPE_KEYBOARD);
        in.input.ki.wVk = new WORD(0);
        in.input.ki.wScan = new WORD(c);
        in.input.ki.dwFlags = new DWORD(KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0));
      }
    }
    user32.SendInput(new DWORD(inputs.length), inputs, inputs[0].size());
  }

  private Optional<String> programOf(HWND hwnd) {
    IntByReference pid = new IntByReference();
    user32.GetWindowThreadProcessId(hwnd, pid);
    return ProcessHandle.of(pid.getValue())
        .flatMap(p -> p.info().command())
        .map(path -> Path.of(path).getFileName().toString().toLowerCase());
  }

  private void copyToClipboard(String plaintext) {
    Toolkit.getDefaultToolkit()
        .getSystemClipboard()
        .setContents(new StringSelection(plaintext), null);
  }

  private boolean delay(int ms) {
    try {
      Thread.sleep(ms);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
