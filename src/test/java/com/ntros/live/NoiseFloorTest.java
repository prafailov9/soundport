package com.ntros.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NoiseFloorTest {

  private static double[] frames(int count, double level) {
    double[] levels = new double[count];
    Arrays.fill(levels, level);
    return levels;
  }

  @Test
  void speechMustBeWellAboveTheRoom() {
    var noise = new NoiseFloor();
    noise.add(frames(80, -55)); // a quiet room
    noise.add(frames(20, -20)); // and some speech

    assertEquals(-40, noise.speechThreshold(), 1e-9);
  }

  @Test
  void aVeryQuietRoomStillNeedsMinus45() {
    var noise = new NoiseFloor();
    noise.add(frames(50, -90));

    assertEquals(NoiseFloor.QUIETEST_SPEECH_DBFS, noise.speechThreshold());
  }

  @Test
  void aNoisyRoomOrNonstopTalkingCapsTheBar() {
    var noise = new NoiseFloor();
    noise.add(frames(50, -25));

    assertEquals(NoiseFloor.LOUDEST_BAR_DBFS, noise.speechThreshold());
  }

  @Test
  void onlyTheLastThirtySecondsCount() {
    var noise = new NoiseFloor();
    noise.add(frames(300, -90));
    noise.add(frames(300, -55)); // the room got louder

    assertEquals(-40, noise.speechThreshold(), 1e-9);
  }
}
