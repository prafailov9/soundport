package com.ntros.server;

import com.ntros.LifeCycle;
import com.ntros.data.ServerSettings;
import com.ntros.data.UploadRequest;
import com.ntros.fileprocessor.FileDispatchingFunction;
import com.ntros.live.LiveClient;
import com.ntros.live.LiveTranscriptionService;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JavalinWebServer implements LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(JavalinWebServer.class);

  // the page sends a UUID; anything else falls back to the socket's own id
  private static final Pattern STREAM_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private final Javalin app;
  private final FileDispatchingFunction uploadCallback;
  private final LiveTranscriptionService liveService;

  public JavalinWebServer(
      ServerSettings serverSettings,
      FileDispatchingFunction uploadCallback,
      LiveTranscriptionService liveService) {
    if (serverSettings == null) {
      throw new IllegalArgumentException("server settings empty");
    }

    this.uploadCallback = uploadCallback;
    this.liveService = liveService;

    SslPlugin ssl =
        new SslPlugin(
            conf -> {
              conf.keystoreFromPath("dev-keystore.p12", "changeit");
              conf.securePort = serverSettings.port();
              conf.insecure = false;
              conf.sniHostCheck = false; // we connect by IP address; this check would reject that
            });

    app =
        Javalin.create(
            config -> {
              config.registerPlugin(ssl);

              // Serves everything in resources/static; index.html is served at "/" automatically
              config.staticFiles.add("/static");

              config.routes.get(
                  "/hello",
                  ctx -> {
                    log.info("Client: {}", ctx.ip());
                    ctx.result("Hello");
                  });

              config.routes.post("/upload", this::handleUpload);

              // A test socket: echoes text back, and reports the size of any binary message
              config.routes.ws(
                  "/ws/echo",
                  ws -> {
                    ws.onConnect(ctx -> log.info("WS connected: {}", ctx.sessionId()));
                    ws.onMessage(ctx -> ctx.send("echo: " + ctx.message()));
                    ws.onBinaryMessage(
                        ctx -> ctx.send("received " + ctx.data().remaining() + " bytes"));
                    ws.onClose(
                        ctx -> log.info("WS closed: {} ({})", ctx.sessionId(), ctx.status()));
                    ws.onError(ctx -> log.error("WS error: {}", ctx.sessionId(), ctx.error()));
                  });

              // Live audio: raw 16 kHz PCM in, {"partial"|"final"|"done"} JSON out. Only the
              // live service sends on this socket, from its one thread
              config.routes.ws(
                  "/ws/audio",
                  ws -> {
                    ws.onConnect(ctx -> liveService.open(streamId(ctx), new WsClient(ctx)));
                    ws.onBinaryMessage(ctx -> liveService.audio(new WsClient(ctx), ctx.data()));
                    ws.onMessage(
                        ctx -> {
                          if ("end".equals(ctx.message())) {
                            liveService.end(new WsClient(ctx));
                          } else {
                            log.warn("Unknown live message: {}", ctx.message());
                          }
                        });
                    ws.onClose(ctx -> liveService.dropped(new WsClient(ctx)));
                    ws.onError(ctx -> log.error("Live socket error", ctx.error()));
                  });
            });
  }

  private void handleUpload(Context ctx) throws Exception {
    log.info("Received upload request");
    String contentType = ctx.contentType();
    if (contentType == null || contentType.isBlank()) {
      ctx.status(400).result("Missing Content-Type header");
      return;
    }
    // ties this upload to the live stream the page recorded alongside it
    String streamId = ctx.header("X-Stream-Id");
    try (InputStream in = ctx.bodyInputStream()) {
      boolean ok = uploadCallback.writeAndDispatch(new UploadRequest(in, contentType, streamId));
      ctx.status(ok ? 204 : 500);
    }
  }

  private static String streamId(WsContext ctx) {
    String id = ctx.queryParam("id");
    return id != null && STREAM_ID.matcher(id).matches() ? id : ctx.sessionId();
  }

  /** One phone connection. WsContext is equal per connection, so this is too. */
  private record WsClient(WsContext ctx) implements LiveClient {

    @Override
    public void send(String json) {
      ctx.send(json);
    }

    @Override
    public void close() {
      ctx.closeSession();
    }
  }

  @Override
  public void start() {
    app.start();
  }

  @Override
  public void stop() {
    app.stop();
  }
}
