package com.ntros.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void plainTextPassesThrough() {
    assertEquals("{\"final\":\"Здравей, свят.\"}", Json.object("final", "Здравей, свят."));
  }

  @Test
  void escapesQuotesBackslashesAndControlCharacters() {
    assertEquals(
        "{\"partial\":\"say \\\"hi\\\" C:\\\\dev\\nnext\\ttab \\u0001\"}",
        Json.object("partial", "say \"hi\" C:\\dev\nnext\ttab \u0001"));
  }
}
