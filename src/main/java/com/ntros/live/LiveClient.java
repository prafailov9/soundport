package com.ntros.live;

/**
 * The phone on the other end of a live stream. Keeps the live code free of web-server types, and
 * lets tests use a fake. Equal clients must mean the same connection: open streams are keyed by it.
 */
public interface LiveClient {

  void send(String json);

  void close();
}
