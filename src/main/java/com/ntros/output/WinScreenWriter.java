package com.ntros.output;

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
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WinScreenWriter implements Runnable, LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(WinScreenWriter.class);

  private static final int WRITE_DELAY_MS = 5_000;
  private static final int KEYEVENTF_KEYUP = 0x0002;
  private static final int KEYEVENTF_UNICODE = 0x0004;
  private static final int VK_RETURN = 0x0D;
  private static final String INPUT_TYPE_KEYBOARD = "ki";
  private final User32 user32 = User32.INSTANCE;

  private final BlockingQueue<TextOut> textQueue;
  private final Thread screenThread;
  private final AtomicBoolean running = new AtomicBoolean(true);
  // todo: change to set
  private static final List<String> PROGRAM_ALLOWLIST =
      List.of("windowsterminal.exe", "claude.exe", "notepad++.exe", "notepad.exe", "obsidian.exe");
  // Enter runs a command or sends a message here, so line breaks are typed as spaces
  private static final Set<String> ENTER_SENDS = Set.of("windowsterminal.exe", "claude.exe");

  public WinScreenWriter(BlockingQueue<TextOut> textQueue) {
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
      TextOut out;
      try {
        out = textQueue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted while reading");
        break;
      }
      if (out.text().isBlank()) {
        continue;
      }
      // spin for roughly 5 seconds, or just look once
      long waitNanos = out.waitForWindow() ? WRITE_DELAY_MS * 1_000_000L : 0;
      tryWriteIn(System.nanoTime() + waitNanos, out);
    }
  }

  /**
   * Polls the WinOS for an allowed window to write in. Checks at least once. If none found in the
   * duration -> Ctrl + C
   *
   * @param untilNanos - time given to find a window to write in
   * @param out - text to write, and the text to copy instead
   */
  private void tryWriteIn(long untilNanos, TextOut out) {
    do {
      var program = programOf(user32.GetForegroundWindow());
      if (program.isPresent() && PROGRAM_ALLOWLIST.contains(program.get())) {
        if (write(out.text(), ENTER_SENDS.contains(program.get()))) {
          return;
        }
        break; // Windows blocked the typing; fall back to the clipboard
      }
      // short delay to allow cpu to breathe
    } while (System.nanoTime() < untilNanos && delay(100));
    copyToClipboard(out.clipboardText());
  }

  /**
   * Writes the text directly in a foreground window's textbox right after already-existing text,
   * without a whitespace. Simulates keyboard input. Line breaks are pressed as Enter, or typed as
   * spaces where Enter would run or send what was typed so far.
   *
   * @param text send to windows for writing
   * @param newlineAsSpace true for programs where Enter runs a command or sends a message
   * @return false if Windows did not accept every key event, e.g. the window runs as administrator
   */
  private boolean write(String text, boolean newlineAsSpace) {
    String typed = text.replace("\r", "");
    if (newlineAsSpace) {
      typed = typed.replace('\n', ' ');
    }
    if (typed.isEmpty()) {
      return true;
    }
    WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(typed.length() * 2);
    int i = 0;
    for (char c : typed.toCharArray()) {
      for (boolean up : new boolean[] {false, true}) {
        WinUser.INPUT in = inputs[i++];
        in.type = new DWORD(INPUT.INPUT_KEYBOARD);
        in.input.setType(INPUT_TYPE_KEYBOARD);
        int upFlag = up ? KEYEVENTF_KEYUP : 0;
        if (c == '\n') {
          // a real Enter key; a unicode 0x0A behaves differently from one program to the next
          in.input.ki.wVk = new WORD(VK_RETURN);
          in.input.ki.wScan = new WORD(0);
          in.input.ki.dwFlags = new DWORD(upFlag);
        } else {
          in.input.ki.wVk = new WORD(0);
          in.input.ki.wScan = new WORD(c);
          in.input.ki.dwFlags = new DWORD(KEYEVENTF_UNICODE | upFlag);
        }
      }
    }
    int sent = user32.SendInput(new DWORD(inputs.length), inputs, inputs[0].size()).intValue();
    if (sent != inputs.length) {
      log.warn("Windows accepted {} of {} key events", sent, inputs.length);
      return false;
    }
    return true;
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
