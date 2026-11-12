package com.ntros.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void plainTextPassesThrough() {
    assertEquals("{\"final\":\"Здравей, свят.\"}", Json.parseRow("final", "Здравей, свят."));
  }

  @Test
  void escapesQuotesBackslashesAndControlCharacters() {
    assertEquals(
        "{\"partial\":\"say \\\"hi\\\" C:\\\\dev\\nnext\\ttab \\u0001\"}",
        Json.parseRow("partial", "say \"hi\" C:\\dev\nnext\ttab \u0001"));
  }

  @Test
  void parsePlainTest() {
    TreeMap<String, String> treeMap = new TreeMap<>();
    treeMap.put("city", "Los Angeles");
    treeMap.put("landmark", "Hollywood");
    treeMap.put("state", "California");
    var res = Json.parsePlain(treeMap);
    System.out.println(res);
    String expected =
"""
{
"city":"Los Angeles",
"landmark":"Hollywood",
"state":"California"
}""";
    assertEquals(expected, res);
  }

  @Test
  void parsePlainLeavesOutNullValues() {
    Map<String, String> pairs = new LinkedHashMap<>();
    pairs.put("state", "failed");
    pairs.put("text", null);
    pairs.put("error", "ffmpeg failed");

    assertEquals("{\n\"state\":\"failed\",\n\"error\":\"ffmpeg failed\"\n}", Json.parsePlain(pairs));
  }

  @Test
  void parsePlainOfNothingIsAnEmptyObject() {
    Map<String, String> pairs = new LinkedHashMap<>();
    pairs.put("text", null);

    assertEquals("{\n}", Json.parsePlain(pairs));
    assertEquals("{\n}", Json.parsePlain(Map.of()));
  }
}
