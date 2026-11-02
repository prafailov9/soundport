package com.ntros.server;

import com.ntros.LifeCycle;
import com.ntros.data.FileStorageSettings;
import com.ntros.data.ServerSettings;
import com.ntros.data.UploadRequest;
import com.ntros.data.Wav;
import com.ntros.fileprocessor.FileDispatchingFunction;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JavalinWebServer implements LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(JavalinWebServer.class);

  private final Map<String, ByteArrayOutputStream> liveAudio = new ConcurrentHashMap<>();

  private final Javalin app;
  private final FileDispatchingFunction uploadCallback;
  private final Path liveDir;
  private final DateTimeFormatter formatter;

  public JavalinWebServer(
      ServerSettings serverSettings,
      FileStorageSettings fileStorageSettings,
      FileDispatchingFunction uploadCallback) {
    if (serverSettings == null) {
      throw new IllegalArgumentException("server settings empty");
    }
    if (fileStorageSettings == null) {
      throw new IllegalArgumentException("file settings empty");
    }

    liveDir = fileStorageSettings.liveDir();
    formatter = fileStorageSettings.formatter();

    this.uploadCallback = uploadCallback;

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

              config.routes.ws(
                  "/ws/audio",
                  ws -> {
                    ws.onConnect(
                        ctx -> {
                          liveAudio.put(ctx.sessionId(), new ByteArrayOutputStream());
                          log.info("Live stream started: {}", ctx.sessionId());
                        });

                    ws.onBinaryMessage(
                        ctx -> {
                          ByteBuffer buf = ctx.data();
                          byte[] bytes = new byte[buf.remaining()];
                          buf.get(bytes);

                          var audio = liveAudio.get(ctx.sessionId());
                          audio.write(bytes);
                          ctx.send(
                              String.format(
                                  "streamed %.1f s", audio.size() / (double) Wav.BYTES_PER_SECOND));
                        });

                    ws.onClose(
                        ctx -> {
                          var audio = liveAudio.remove(ctx.sessionId());
                          if (audio == null || audio.size() == 0) return;
                          Files.createDirectories(liveDir);
                          Path file =
                              liveDir.resolve(LocalDateTime.now().format(formatter) + ".wav");
                          Wav.write(file, audio.toByteArray());
                          log.info(
                              "Live stream saved: {} ({} s)",
                              file,
                              audio.size() / Wav.BYTES_PER_SECOND);
                        });
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

  @Override
  public void start() {
    app.start();
  }

  @Override
  public void stop() {
    app.stop();
  }
}
