package me.sattle.checkout.internal;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * HTTP as every implementation asks it (SPEC.md §15): a GET with {@code Accept: application/json}
 * and {@code User-Agent: sattleme-Checkout/1}; no redirect followed (every URL is checked before it
 * is asked: a redirect would skip the check, and is no answer); the whole request, body included,
 * bounded by the HTTP timeout; the body read up to 1 MiB, a larger one dropped unread beyond (no
 * answer). The client is the platform's {@link HttpClient}, injectable (proxies, tests); it must
 * follow no redirect itself. Every URL asked is checked where it comes from: an https URL (SPEC.md
 * §15; the relays', the Lightning address's, the rate sources', mempool.space's, BlockCypher's), or
 * under the merchant's Bitcoin node's URL, which follows a rule of its own (SPEC.md §31, {@link
 * me.sattle.checkout.internal.onchain.NodeUrl}): the only URLs asked in plain {@code http}, to a
 * host of a local network, through the same client.
 */
public final class Http {
  private final HttpClient client;
  private final long timeoutMs;

  /**
   * HTTP over {@code client}.
   *
   * @param client the platform's client, following no redirect
   * @param timeoutMs the bound of each request, body included
   */
  public Http(HttpClient client, long timeoutMs) {
    this.client = client;
    this.timeoutMs = timeoutMs;
  }

  /** The most an answer's head may hold (SPEC.md §15): 10 MiB. */
  public static final long MAX_HEAD_BYTES = 10L << 20;

  /** Java's own setting of the bound, and its default (384 KiB). */
  private static final String HEAD_SETTING = "jdk.http.maxHeaderSize";

  private static final long JAVA_HEAD_DEFAULT = 393_216;

  /**
   * The bound Java's own HTTP client ({@code java.net.http}, its WebSocket handshakes included)
   * puts on an answer's head, its status line and headers: {@code jdk.http.maxHeaderSize} bytes,
   * 384 KiB unless set (an invalid setting is the default), from Java 17.0.11, 21.0.3 and 22.0.1
   * on; before them (18 to 20 included), and set to 0 or less, none, and it reads a head of any
   * size into memory (a chunked body's size lines it bounds at 2 050 bytes: 17.0.10 and 21.0.12
   * alike).
   *
   * @param version the version of Java
   * @param setting {@code jdk.http.maxHeaderSize} as set, or null
   * @return the bound in bytes, 0 for none
   */
  public static long javaHeadBound(Runtime.Version version, String setting) {
    if (!boundsHeads(version)) return 0;
    long bound;
    try {
      bound = setting == null ? JAVA_HEAD_DEFAULT : Integer.parseInt(setting.trim());
    } catch (NumberFormatException e) {
      bound = JAVA_HEAD_DEFAULT;
    }
    return Math.max(bound, 0);
  }

  /** Whether Java's own HTTP client of {@code version} has {@code jdk.http.maxHeaderSize}. */
  private static boolean boundsHeads(Runtime.Version version) {
    int feature = version.feature();
    return feature >= 23
        || (feature == 22 && version.update() >= 1)
        || (feature == 21 && version.update() >= 3)
        || (feature == 17 && version.update() >= 11);
  }

  /**
   * {@code jdk.http.maxHeaderSize} as this Java reads it: the system property, else the line of its
   * {@code conf/net.properties}, else null.
   *
   * @return the setting, or null
   */
  public static String javaHeadSetting() {
    String property = System.getProperty(HEAD_SETTING);
    if (property != null) return property;
    java.nio.file.Path file =
        java.nio.file.Path.of(System.getProperty("java.home"), "conf", "net.properties");
    java.util.Properties properties = new java.util.Properties();
    try (java.io.Reader reader =
        java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.ISO_8859_1)) {
      properties.load(reader);
    } catch (java.io.IOException | RuntimeException e) {
      return null;
    }
    return properties.getProperty(HEAD_SETTING);
  }

  /**
   * Whether Java's own HTTP client bounds an answer's head, in this Java, to 10 MiB at most
   * (SPEC.md §15: a client that reads it without a bound is given one).
   *
   * @return the reason it does not, or null when it does
   */
  public static String javaHeadUnbounded() {
    if (!boundsHeads(Runtime.version())) {
      return "Java "
          + Runtime.version()
          + " reads an HTTP answer's head without a bound: run Java 17.0.11, 21.0.3, 22.0.1 or"
          + " later";
    }
    long bound = javaHeadBound(Runtime.version(), javaHeadSetting());
    if (bound > 0 && bound <= MAX_HEAD_BYTES) return null;
    return "jdk.http.maxHeaderSize is "
        + (bound == 0 ? "0 or less (no bound)" : "above 10 MiB")
        + ": unset it (384 KiB), or set it from 1 to 10 MiB";
  }

  /**
   * An answer: its status and its body (1 MiB at most).
   *
   * @param status the HTTP status
   * @param body the body's bytes
   */
  public record Answer(int status, byte[] body) {
    /**
     * Whether the status is 2xx.
     *
     * @return true for a success
     */
    public boolean ok() {
      return status >= 200 && status < 300;
    }

    /**
     * The body as JSON (SPEC.md §15): one leading byte order mark ignored, strict UTF-8, JSON.
     *
     * @return its value, or null when it is not JSON in UTF-8
     */
    public JsonValue json() {
      return Json.parseBody(body);
    }

    /**
     * The body as text (an explorer's height, the one plain-text answer): strict UTF-8.
     *
     * @return its text, or null when it is not UTF-8
     */
    public String text() {
      return Json.bodyText(body);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Answer
          && status == ((Answer) other).status
          && java.util.Arrays.equals(body, ((Answer) other).body);
    }

    @Override
    public int hashCode() {
      return 31 * status + java.util.Arrays.hashCode(body);
    }

    @Override
    public String toString() {
      return "Answer[status=" + status + ", body=" + body.length + " bytes]";
    }
  }

  /**
   * GETs {@code url} (checked), at once: the future fails with {@link NetworkException} when there
   * is no answer in time (the resolution of the host's name included), a redirect, a body too
   * large; cancelling it aborts the request. Asked from an interrupted thread (a call stopped), it
   * asks nothing: the future is cancelled.
   *
   * @param url the URL
   * @param headers more headers (CoinGecko's Demo key)
   * @return the answer
   */
  public CompletableFuture<Answer> get(String url, Map<String, String> headers) {
    CompletableFuture<Answer> answer = new CompletableFuture<>();
    // A call stopped (its thread interrupted) asks nothing more; whoever waits learns of it.
    if (Thread.currentThread().isInterrupted()) {
      answer.cancel(false);
      return answer;
    }
    String host = Urls.hostOf(url);
    HttpRequest request;
    try {
      HttpRequest.Builder builder =
          // As fetch asks it: its fragment dropped, what URI refuses percent-encoded.
          HttpRequest.newBuilder(Urls.requestUri(url))
              .GET()
              .timeout(Duration.ofMillis(timeoutMs))
              .header("Accept", "application/json")
              .header("User-Agent", Constants.USER_AGENT);
      // Plain http (the merchant's node on its own network): HTTP/1.1, never the upgrade to
      // HTTP/2 that the platform's client otherwise offers over it (h2c).
      if (url.startsWith("http://")) builder.version(HttpClient.Version.HTTP_1_1);
      for (Map.Entry<String, String> header : headers.entrySet()) {
        builder.header(header.getKey(), header.getValue());
      }
      request = builder.build();
    } catch (IllegalArgumentException e) {
      answer.completeExceptionally(new NetworkException("No answer from " + host, e));
      return answer;
    }
    // The whole request, from the resolution of the host's name to the body's last byte: the
    // platform's own request timeout stops at the headers, and nothing bounds a name's resolution.
    ScheduledFuture<?> timer =
        Tasks.schedule(
            timeoutMs,
            () ->
                answer.completeExceptionally(
                    new NetworkException("No answer from " + host + " in time")));
    AtomicReference<CompletableFuture<HttpResponse<byte[]>>> sending = new AtomicReference<>();
    // Failed in time or cancelled (an interrupted call): the request aborted.
    answer.whenComplete((ignored, error) -> {
      timer.cancel(false);
      CompletableFuture<HttpResponse<byte[]>> sent = sending.get();
      if (error != null && sent != null) sent.cancel(true);
    });
    // Sent from a thread of the package's: a client may resolve the host's name on the calling
    // thread before sendAsync returns (the platform's does, with an executor that runs its tasks
    // there); neither the caller nor the timer waits for it.
    Tasks.WORKERS.execute(() -> send(request, host, answer, sending));
    return answer;
  }

  private void send(
      HttpRequest request,
      String host,
      CompletableFuture<Answer> answer,
      AtomicReference<CompletableFuture<HttpResponse<byte[]>>> sending) {
    if (answer.isDone()) return;
    CompletableFuture<HttpResponse<byte[]>> sent;
    try {
      sent = client.sendAsync(request, info -> new BoundedBody());
    } catch (RuntimeException e) {
      answer.completeExceptionally(new NetworkException("No answer from " + host, e));
      return;
    }
    sending.set(sent);
    // Over meanwhile: aborted.
    if (answer.isDone()) {
      sent.cancel(true);
      return;
    }
    sent.whenComplete((response, error) -> {
      if (error != null) {
        Throwable cause =
            error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        boolean tooLarge = false;
        for (Throwable t = cause; t != null; t = t.getCause()) tooLarge |= t instanceof TooLarge;
        answer.completeExceptionally(
            tooLarge
                ? new NetworkException(
                    host + " answered more than " + Constants.MAX_HTTP_BODY_BYTES + " bytes")
                : new NetworkException("No answer from " + host, cause));
        return;
      }
      int status = response.statusCode();
      if (status >= 300 && status < 400) {
        answer.completeExceptionally(
            new NetworkException(host + " answered a redirect (HTTP " + status + ")"));
        return;
      }
      answer.complete(new Answer(status, response.body()));
    });
  }

  /** A body larger than 1 MiB: dropped, unread beyond. */
  private static final class TooLarge extends RuntimeException {
    private static final long serialVersionUID = 1L;

    TooLarge() {
      super("Body larger than 1 MiB", null, false, false);
    }
  }

  /** A body read up to 1 MiB, one buffer asked for at a time; beyond, the request is cancelled. */
  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) return;
      for (ByteBuffer buffer : buffers) {
        int remaining = buffer.remaining();
        if (bytes.size() + (long) remaining > Constants.MAX_HTTP_BODY_BYTES) {
          subscription.cancel();
          body.completeExceptionally(new TooLarge());
          return;
        }
        byte[] chunk = new byte[remaining];
        buffer.get(chunk);
        bytes.write(chunk, 0, chunk.length);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
