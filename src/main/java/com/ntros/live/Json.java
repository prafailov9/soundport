package com.ntros.live;

/** Just enough JSON for the messages to the page; one helper doesn't justify adding Jackson. */
final class Json {

  private Json() {}

  static String object(String key, String value) {
    return "{\"" + key + "\":\"" + escape(value) + "\"}";
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
