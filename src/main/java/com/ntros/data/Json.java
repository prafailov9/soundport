package com.ntros.data;

import java.util.Map;

/** Just enough JSON for the messages to the page; one helper doesn't justify adding Jackson. */
public final class Json {

  private Json() {}

  public static String parseRow(String key, String value) {
    return "{\"" + key + "\":\"" + escape(value) + "\"}";
  }

  // works for single non-nested objects && no arrays. A null value leaves its key out
  public static String parsePlain(Map<String, String> pairs) {
    StringBuilder stringBuilder = new StringBuilder();
    // open
    stringBuilder.append("{\n");
    boolean first = true;
    for (var e : pairs.entrySet()) {
      if (e.getValue() == null) {
        continue;
      }
      // a comma between pairs, never after the last one
      if (!first) {
        stringBuilder.append(",\n");
      }
      first = false;
      stringBuilder
          .append("\"")
          .append(e.getKey())
          .append("\":\"")
          .append(escape(e.getValue()))
          .append("\"");
    }
    // close
    stringBuilder.append(first ? "}" : "\n}");
    return stringBuilder.toString();
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.toString();
  }
}
