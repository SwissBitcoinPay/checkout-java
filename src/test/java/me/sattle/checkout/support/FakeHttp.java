package me.sattle.checkout.support;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * An HTTP client for tests: each request goes to a route, which answers (or throws: no answer). The
 * body is handed to the package's subscriber in parts, as the platform's client does, and nothing
 * reaches the network.
 */
public final class FakeHttp extends HttpClient {
  /**
   * An answer.
   *
   * @param status the HTTP status
   * @param body its bytes
   */
  public record Answer(int status, byte[] body) {
    /**
     * A JSON answer.
     *
     * @param json the body
     * @return the answer, 200
     */
    public static Answer json(JsonValue json) {
      return json(200, json);
    }

    /**
     * A JSON answer with a status.
     *
     * @param status the status
     * @param json the body
     * @return the answer
     */
    public static Answer json(int status, JsonValue json) {
      return new Answer(status, Js.write(json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A text answer.
     *
     * @param status the status
     * @param text the body
     * @return the answer
     */
    public static Answer text(int status, String text) {
      return new Answer(status, text.getBytes(StandardCharsets.UTF_8));
    }
  }

  /** Answers a request. */
  @FunctionalInterface
  public interface Route {
    /**
     * The answer to {@code uri}.
     *
     * @param uri the URL asked
     * @return the answer
     * @throws Exception no answer
     */
    Answer answer(URI uri) throws Exception;
  }

  /**
   * A request the package made.
   *
   * @param uri its URL
   * @param headers its headers
   */
  public record Call(URI uri, HttpHeaders headers) {
    /**
     * The value of a query parameter, decoded as a form.
     *
     * @param name its name
     * @return the value, or null
     */
    public String query(String name) {
      String query = uri.getRawQuery();
      if (query == null) return null;
      for (String pair : query.split("&")) {
        int equals = pair.indexOf('=');
        String key = equals < 0 ? pair : pair.substring(0, equals);
        if (java.net.URLDecoder.decode(key, StandardCharsets.UTF_8).equals(name)) {
          return equals < 0
              ? ""
              : java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
        }
      }
      return null;
    }
  }

  private final Route route;
  private final Redirect redirect;
  private final ExecutorService threads =
      Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fake-http");
        thread.setDaemon(true);
        return thread;
      });

  /** Every request, in order. */
  public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

  /** Every request as the package built it (its HTTP version among the rest), in order. */
  public final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

  /**
   * A request as the package holds it: its URL, and the future {@code sendAsync} gave (cancelled
   * when the package aborts it).
   *
   * @param uri its URL
   * @param result its future
   */
  public record Sent(URI uri, CompletableFuture<?> result) {}

  /** Every request's future, in order. */
  public final List<Sent> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** How many bytes of a body each part holds. */
  public int partBytes = 16 * 1024;

  /** Bytes of bodies handed to the package. */
  public final AtomicLong delivered = new AtomicLong();

  /**
   * A client answering with {@code route}.
   *
   * @param route the route
   */
  public FakeHttp(Route route) {
    this(route, Redirect.NEVER);
  }

  /**
   * A client answering with {@code route}, saying it follows {@code redirect}.
   *
   * @param route the route
   * @param redirect its redirect policy
   */
  public FakeHttp(Route route, Redirect redirect) {
    this.route = route;
    this.redirect = redirect;
  }

  /**
   * The URLs asked, as strings.
   *
   * @return the URLs
   */
  public List<String> urls() {
    List<String> urls = new ArrayList<>();
    synchronized (calls) {
      for (Call call : calls) urls.add(call.uri().toString());
    }
    return urls;
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return redirect;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    try {
      return SSLContext.getDefault();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.of(threads);
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    try {
      return sendAsync(request, handler).get();
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof IOException io) throw io;
      throw new IOException(e.getCause());
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    calls.add(new Call(request.uri(), request.headers()));
    requests.add(request);
    CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
    sent.add(new Sent(request.uri(), result));
    threads.execute(() -> {
      Answer answer;
      try {
        answer = route.answer(request.uri());
      } catch (Exception e) {
        result.completeExceptionally(new IOException("fetch failed: " + request.uri(), e));
        return;
      }
      HttpHeaders headers =
          HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true);
      HttpResponse.BodySubscriber<T> subscriber = handler.apply(new Info(answer.status(), headers));
      deliver(subscriber, answer.body());
      subscriber
          .getBody()
          .whenComplete((body, error) -> {
            if (error != null) {
              result.completeExceptionally(
                  new IOException(
                      "body failed",
                      error instanceof CompletionException && error.getCause() != null
                          ? error.getCause()
                          : error));
            } else {
              result.complete(new Response<>(request, answer.status(), headers, body));
            }
          });
    });
    return result;
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return sendAsync(request, handler);
  }

  /** Hands {@code body} to {@code subscriber} in parts, as it asks for them. */
  private <T> void deliver(HttpResponse.BodySubscriber<T> subscriber, byte[] body) {
    AtomicLong demand = new AtomicLong();
    AtomicBoolean cancelled = new AtomicBoolean();
    int[] offset = {0};
    boolean[] completed = {false};
    Object lock = new Object();
    Runnable pump =
        () -> {
          synchronized (lock) {
            while (!cancelled.get() && !completed[0] && demand.get() > 0) {
              if (offset[0] >= body.length) {
                completed[0] = true;
                subscriber.onComplete();
                return;
              }
              int length = Math.min(partBytes, body.length - offset[0]);
              ByteBuffer part = ByteBuffer.wrap(body, offset[0], length).slice();
              offset[0] += length;
              demand.decrementAndGet();
              delivered.addAndGet(length);
              subscriber.onNext(List.of(part));
            }
          }
        };
    subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            demand.addAndGet(n);
            threads.execute(pump);
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }
        });
  }

  /** Stops the client's threads. */
  public void shutdown() {
    threads.shutdownNow();
  }

  private record Info(int statusCode, HttpHeaders headers) implements HttpResponse.ResponseInfo {
    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }

  private record Response<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
      implements HttpResponse<T> {
    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }
}
