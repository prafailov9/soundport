package com.ntros.server;

import com.ntros.LifeCycle;
import com.ntros.data.ServerSettings;
import com.ntros.data.UploadRequest;
import com.ntros.fileprocessor.FileDispatchingFunction;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebServer implements LifeCycle {

  private static final Logger log = LoggerFactory.getLogger(WebServer.class);

  private final HttpServer httpServer;
  private final FileDispatchingFunction uploadCallback;

  public WebServer(ServerSettings settings, FileDispatchingFunction fn) {
    if (settings == null) {
      throw new IllegalArgumentException("server settings empty");
    }

    // init
    httpServer = createServer(settings.port());
    httpServer.setExecutor(Executors.newFixedThreadPool(settings.workersCount()));

    this.uploadCallback = fn;

    // register endpoints
    helloEndpoint();
    indexEndpoint();
    uploadRecordingEndpoint();
  }

  @Override
  public void start() {
    httpServer.start();
  }

  @Override
  public void stop() throws InterruptedException {
    httpServer.stop(0);
  }

  private HttpServer createServer(int port) {
    try {
      HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(createSslContext()));
      return server;
    } catch (Exception e) {
      log.error("Failed to create https server", e);
      throw new RuntimeException(e);
    }
  }

  private void helloEndpoint() {
    httpServer.createContext(
        "/hello",
        logged(
            exchange -> {
              log.info("Client: {}", exchange.getRemoteAddress());
              Response res = new Response(200, "Hello".getBytes(StandardCharsets.UTF_8));
              var bytes = res.responseBytes;
              exchange.sendResponseHeaders(res.code, bytes.length);

              try (var out = exchange.getResponseBody()) {
                out.write(bytes);
              }
            }));
  }

  private void indexEndpoint() {
    httpServer.createContext(
        "/",
        logged(
            exchange -> {
              if (!exchange.getRequestURI().getPath().equals("/")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
              }

              try (InputStream in = WebServer.class.getResourceAsStream("/static/index.html")) {
                if (in == null) {
                  log.error("static/index.html not found on classpath");
                  exchange.sendResponseHeaders(500, -1);
                  exchange.close();
                  return;
                }

                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                  out.write(bytes);
                }
              }
            }));
  }

  private void uploadRecordingEndpoint() {
    httpServer.createContext(
        "/upload",
        logged(
            exchange -> {
              log.info("Received upload request");
              if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // 405 = wrong method
                exchange.close();
                return;
              }
              String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

              if (contentType == null || contentType.isEmpty()) {
                String err = "Missing Content-Type header";
                exchange.sendResponseHeaders(500, err.getBytes().length);
                exchange.close();
                log.info(err);
                return;
              }
              String streamId = exchange.getRequestHeaders().getFirst("X-Stream-Id");
              int code;
              try (var in = exchange.getRequestBody()) {
                code =
                    getStatusCode(
                        uploadCallback.writeAndDispatch(
                            new UploadRequest(in, contentType, streamId)));
              }
              exchange.sendResponseHeaders(code, -1); // 204 = success, nothing to send back
              exchange.close();
            }));
  }

  private int getStatusCode(boolean res) {
    if (!res) {
      return 500;
    }
    return 204;
  }

  private SSLContext createSslContext() throws Exception {
    char[] password = "changeit".toCharArray();

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var in = new FileInputStream("dev-keystore.p12")) {
      keyStore.load(in, password);
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, password);

    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), null, null);
    return ctx;
  }

  private static HttpHandler logged(HttpHandler handler) {
    return exchange -> {
      try {
        handler.handle(exchange);
      } catch (Exception e) {
        log.error("Handler failed for {}", exchange.getRequestURI(), e);
        exchange.close();
      }
    };
  }

  private record Response(int code, byte[] responseBytes) {}
}
