package com.ntros.server;

import static com.ntros.data.Command.WRITE_2_FILE;
import static com.ntros.data.Command.WRITE_2_SCREEN;

import com.ntros.LifeCycle;
import com.ntros.data.Job;
import com.ntros.data.Json;
import com.ntros.data.ServerSettings;
import com.ntros.data.UploadRequest;
import com.ntros.data.UploadResponse;
import com.ntros.fileprocessor.FileDispatchingFunction;
import com.ntros.live.LiveClient;
import com.ntros.live.LiveTranscriptionService;
import com.ntros.transcription.TranscriptionStatusService;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
  private final TranscriptionStatusService transcriptionStatusService;

  public JavalinWebServer(
      ServerSettings serverSettings,
      LiveTranscriptionService liveService,
      TranscriptionStatusService transcriptionStatusService,
      FileDispatchingFunction uploadCallback) {
    if (serverSettings == null) {
      throw new IllegalArgumentException("server settings empty");
    }

    this.uploadCallback = uploadCallback;
    this.liveService = liveService;
    this.transcriptionStatusService = transcriptionStatusService;

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
              // HTTP
              config.routes.get("/upload-audio", ctx -> ctx.redirect("/upload-audio.html"));
              config.routes.post("/upload-audio", this::handleUploadAudio);
              config.routes.get("/jobs/{id}", this::handleJobStatus);
              config.routes.post("/upload", this::handleUpload);
              // WS

              config.routes.ws("/ws/audio", this::handleAudioStream);
            });
  }

  /**
   * Live audio: raw 16 kHz PCM in, {"partial"|"final"|"done"} JSON out. Only the live service sends
   * on this socket, from its one thread
   */
  private void handleAudioStream(WsConfig ws) {
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
  }

  private void handleUploadAudio(Context ctx) throws IOException {
    log.info("Client: {}", ctx.ip());
    UploadedFile file = ctx.uploadedFile("file");
    if (file == null) {
      ctx.status(400).result("No file received");
      return;
    }
    log.info(
        "Received audio file: {} ({} bytes, {})", file.filename(), file.size(), file.contentType());
    // the job exists before the worker can see the upload, so the page can always look it up
    String jobId = transcriptionStatusService.create();
    var res =
        upload(new UploadRequest(jobId, file.content(), file.contentType(), null, WRITE_2_FILE));
    if (!res.success()) {
      transcriptionStatusService.failed(jobId, "Couldn't save the upload");
      ctx.status(500).result("Couldn't save the upload");
      return;
    }
    // transcribing takes a while: answer now, the page asks GET /jobs/{id} for the result
    ctx.status(202).contentType("application/json").result(Json.parseRow("id", jobId));
  }

  /** How an uploaded file is doing: state, then the text and file name, or the error. */
  private void handleJobStatus(Context ctx) {
    Job job = transcriptionStatusService.get(ctx.pathParam("id")).orElse(null);
    if (job == null) {
      ctx.status(404).result("Unknown job"); // e.g. the server restarted since the upload
      return;
    }
    Map<String, String> answer = new LinkedHashMap<>();
    answer.put("state", job.state().name().toLowerCase(Locale.ROOT));
    answer.put("text", job.text());
    answer.put("file", job.file() == null ? null : job.file().getFileName().toString());
    answer.put("error", job.error());
    ctx.contentType("application/json").result(Json.parsePlain(answer));
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
    var res =
        upload(new UploadRequest(null, ctx.bodyInputStream(), contentType, streamId, WRITE_2_SCREEN));
    ctx.status(res.success() ? 204 : 500);
  }

  /** Saves and queues the upload, then closes its stream. The caller picks the answer. */
  private UploadResponse upload(UploadRequest uploadRequest) throws IOException {
    try (var _ = uploadRequest.is()) {
      return uploadCallback.dispatch(uploadRequest);
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
