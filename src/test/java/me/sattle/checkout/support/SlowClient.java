package me.sattle.checkout.support;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The platform's client, slow to resolve a name: {@code sendAsync} and {@code buildAsync} block the
 * calling thread {@code blockMs} before they go on (as the platform's own does when its executor
 * runs its tasks on the calling thread: the name is resolved first, and nothing bounds that); with
 * {@code hang}, the opening of a WebSocket never ends (a resolution on the client's own threads,
 * which its own timeout cannot cut short).
 */
public final class SlowClient extends HttpClient {
  private final HttpClient inner;
  private final long blockMs;
  private final boolean hang;

  /** How many calls were held. */
  public final AtomicInteger held = new AtomicInteger();

  /**
   * A client over {@code inner}.
   *
   * @param inner the platform's client
   * @param blockMs how long each call blocks its caller
   * @param hang whether the opening of a WebSocket never ends
   */
  public SlowClient(HttpClient inner, long blockMs, boolean hang) {
    this.inner = inner;
    this.blockMs = blockMs;
    this.hang = hang;
  }

  private void block() {
    held.incrementAndGet();
    try {
      Thread.sleep(blockMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return inner.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return inner.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return inner.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return inner.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return inner.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return inner.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return inner.authenticator();
  }

  @Override
  public Version version() {
    return inner.version();
  }

  @Override
  public Optional<Executor> executor() {
    return inner.executor();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    block();
    return inner.send(request, handler);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    block();
    return inner.sendAsync(request, handler);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    block();
    return inner.sendAsync(request, handler, pushPromiseHandler);
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    WebSocket.Builder builder = inner.newWebSocketBuilder();
    return new WebSocket.Builder() {
      @Override
      public WebSocket.Builder header(String name, String value) {
        builder.header(name, value);
        return this;
      }

      @Override
      public WebSocket.Builder connectTimeout(Duration timeout) {
        builder.connectTimeout(timeout);
        return this;
      }

      @Override
      public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
        builder.subprotocols(mostPreferred, lesserPreferred);
        return this;
      }

      @Override
      public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
        block();
        if (hang) return new CompletableFuture<>();
        return builder.buildAsync(uri, listener);
      }
    };
  }
}
