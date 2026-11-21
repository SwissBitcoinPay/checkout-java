package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.onchain.Explorer;
import me.sattle.checkout.internal.onchain.NodeUrl;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.RawServer;
import me.sattle.checkout.support.SlowClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP as every implementation asks it (SPEC.md §15), over the platform's {@link HttpClient}
 * against a server that writes its answers by hand: the headers, no redirect followed, the whole
 * request bounded by the timeout (a stalled body too), a body of more than 1 MiB dropped unread
 * beyond, one byte order mark ignored and no more, a body that is not UTF-8 no JSON.
 */
class HttpTest {
  private static final int MIB = Constants.MAX_HTTP_BODY_BYTES;

  private final HttpClient client =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private RawServer server;
  private RawServer target;

  @AfterEach
  void stop() {
    if (server != null) server.close();
    if (target != null) target.close();
  }

  /** A server answering {@code status} with {@code body}, as JSON. */
  private void serve(int status, byte[] body) throws Exception {
    server =
        new RawServer((socket, request) -> {
          RawServer.write(
              socket,
              "HTTP/1.1 "
                  + status
                  + " Whatever\r\nContent-Type: application/json\r\nContent-Length: "
                  + body.length
                  + "\r\nConnection: close\r\n\r\n");
          OutputStream out = socket.getOutputStream();
          out.write(body);
          out.flush();
        });
  }

  private Http.Answer get(long timeoutMs) throws Exception {
    return new Http(client, timeoutMs)
        .get(server.url("http") + "x", Map.of())
        .get(10, TimeUnit.SECONDS);
  }

  private NetworkException failure(long timeoutMs) throws Exception {
    try {
      get(timeoutMs);
    } catch (ExecutionException e) {
      return assertInstanceOf(NetworkException.class, e.getCause());
    }
    throw new AssertionError("an answer");
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) length += part.length;
    byte[] out = new byte[length];
    int at = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, at, part.length);
      at += part.length;
    }
    return out;
  }

  private static final byte[] BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};

  /**
   * The merchant's Bitcoin node on its own network, asked through the client given: here one whose
   * proxy is the test's server, as an integrator's egress proxy would be.
   */
  private Explorer.Source nodeThroughProxy(String nodeUrl) {
    return Explorer.Source.node(NodeUrl.parse(nodeUrl));
  }

  private HttpClient proxiedClient() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.port())))
        .build();
  }

  @Test
  void asksTheMerchantsNodeInPlainHttpThroughTheClientItIsGiven() throws Exception {
    serve(200, utf8("970000"));
    long height =
        Explorer.chainHeight(
                new Http(proxiedClient(), 2000), nodeThroughProxy("http://192.168.1.10:3006"))
            .get(10, TimeUnit.SECONDS);
    assertEquals(970_000, height);
    RawServer.Request request = server.requests.get(0);
    // Through the client's proxy, in plain http, HTTP/1.1: never an upgrade to HTTP/2.
    assertEquals("GET http://192.168.1.10:3006/api/blocks/tip/height HTTP/1.1", request.line());
    assertEquals(List.of(), request.all("Upgrade"));
    assertEquals(List.of("application/json"), request.all("Accept"));
    assertEquals(List.of(Constants.USER_AGENT), request.all("User-Agent"));
  }

  @Test
  void followsNoRedirectOfTheMerchantsNode() throws Exception {
    server =
        new RawServer((socket, request) ->
            RawServer.write(
                socket,
                "HTTP/1.1 302 Found\r\nLocation: http://192.168.1.11/api/blocks/tip/height\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n"));
    ExecutionException error =
        assertThrows(
            ExecutionException.class,
            () ->
                Explorer.chainHeight(
                        new Http(proxiedClient(), 2000), nodeThroughProxy("http://10.0.0.2"))
                    .get(10, TimeUnit.SECONDS));
    assertTrue(
        error.getCause().getMessage().contains("answered a redirect (HTTP 302)"),
        error.getCause().getMessage());
    assertEquals(1, server.requests.size());
  }

  @Test
  void asksForJsonWithTheUserAgentOfEveryImplementation() throws Exception {
    serve(200, utf8("{\"tag\":\"payRequest\"}"));
    Http.Answer answer = get(2000);
    assertEquals(200, answer.status());
    assertEquals(Js.obj("tag", "payRequest"), answer.json());
    RawServer.Request request = server.requests.get(0);
    assertTrue(request.line().startsWith("GET /x HTTP/1.1"), request.line());
    assertEquals(List.of("application/json"), request.all("Accept"));
    // Exactly one: the platform's own replaced.
    assertEquals(List.of(Constants.USER_AGENT), request.all("User-Agent"));
  }

  @Test
  void asksAUrlJavasUriRefusesAsItIsItsFragmentNeverSent() throws Exception {
    // SPEC.md §15 takes [ and ] after the host, and a second #: java.net.URI does not. Such a
    // verify URL is asked, its fragment never sent, a [ or ] of its path percent-encoded.
    serve(200, utf8("{\"status\":\"OK\"}"));
    Http http = new Http(client, 2000);
    for (String path : List.of("lnurlp/verify/[9c41]", "lnurlp/verify/9c41#a#b", "cb?k=[1]#x")) {
      assertEquals(
          200, http.get(server.url("http") + path, Map.of()).get(10, TimeUnit.SECONDS).status());
    }
    assertEquals(
        List.of(
            "GET /lnurlp/verify/%5B9c41%5D HTTP/1.1",
            "GET /lnurlp/verify/9c41 HTTP/1.1", "GET /cb?k=[1] HTTP/1.1"),
        server.requests.stream().map(RawServer.Request::line).toList());
  }

  /** A server that answers its request with {@code start}, then {@code size} bytes of {@code a}. */
  private void serveHead(String start, long size) throws Exception {
    server =
        new RawServer((socket, request) -> {
          OutputStream out = socket.getOutputStream();
          out.write(utf8(start));
          byte[] block = new byte[1 << 16];
          java.util.Arrays.fill(block, (byte) 'a');
          try {
            for (long sent = 0; sent < size; sent += block.length) out.write(block);
            out.flush();
            Thread.sleep(20_000);
          } catch (java.io.IOException e) {
            // The client hung up.
          }
        });
  }

  @Test
  void boundsTheHeadOfAnAnswerAHeaderLineOrAChunkSizeLineLargerIsNoAnswer() throws Exception {
    // SPEC.md §15: the status line, the headers and a chunked body's size lines, 10 MiB at most;
    // Java's own client bounds them far lower (384 KiB, and 2 050 bytes a size line).
    assertNull(Http.javaHeadUnbounded());
    long bound = Http.javaHeadBound(Runtime.version(), Http.javaHeadSetting());
    for (String start :
        List.of(
            "HTTP/1.1 200 OK\r\nX-Big: ",
            "HTTP/1.1 200 ",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;e=")) {
      serveHead(start, Math.max(bound + 1, 12L << 20));
      long started = System.nanoTime();
      NetworkException error = failure(10_000);
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(ms < 5000, ms + " ms: " + error.getMessage());
      server.close();
      server = null;
    }
  }

  /**
   * A server that answers with {@code start}, {@code unit} until {@code size} bytes, then holds.
   */
  private void serveRepeated(String start, String unit, long size, String end) throws Exception {
    server =
        new RawServer((socket, request) -> {
          OutputStream out = socket.getOutputStream();
          out.write(utf8(start));
          long units = Math.min((1 << 16) / unit.length(), size / unit.length());
          byte[] block = utf8(unit.repeat((int) Math.max(1, units)));
          try {
            for (long sent = 0; sent < size; sent += block.length) out.write(block);
            out.write(utf8(end));
            out.flush();
            Thread.sleep(20_000);
          } catch (java.io.IOException e) {
            // The client hung up.
          }
        });
  }

  /**
   * Whether Java's own client of {@code version} takes no trailer: the CRLF that ends a chunked
   * body must follow its last chunk (checked on 17.0.21, 21.0.13 and 25.0.5). Before (21.0.12, for
   * one), it gives the body once two bytes followed the last chunk, the trailers left unread on the
   * connection: none of them held.
   */
  private static boolean takesNoTrailer(Runtime.Version version) {
    int feature = version.feature();
    return (feature == 17 && version.update() >= 21)
        || (feature == 21 && version.update() >= 13)
        || (feature == 25 && version.update() >= 5);
  }

  @Test
  void boundsWhatItHoldsOfAChunkedBodysTrailersAndSizeLinesTheirExcessNoAnswer() throws Exception {
    // SPEC.md §15 (checkout-spec da3bdb8): what the client holds of a chunked body's size lines
    // and trailers counts toward the head's 10 MiB. Java's own client holds no more than 2 050
    // bytes of a size line, leading zeros and extension included, and no trailer: 11 MiB of
    // either is no answer (or, before Java takes no trailer, the body, the trailers never read).
    String chunked =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n";
    long size = 11L << 20;
    serveRepeated(chunked + "2\r\n{}\r\n0\r\n", "X-T: " + "a".repeat(1017) + "\r\n", size, "\r\n");
    long started = System.nanoTime();
    if (takesNoTrailer(Runtime.version())) {
      failure(10_000);
    } else {
      assertEquals(Js.obj(), get(10_000).json());
    }
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    server.close();
    serveRepeated(chunked, "0", size, "2\r\n{}\r\n0\r\n\r\n");
    started = System.nanoTime();
    failure(10_000);
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    server.close();
    // A size line of 100 leading zeros, under the bound: an answer (the server makes valid ones).
    serveRepeated(chunked, "0", 100, "2\r\n{}\r\n0\r\n\r\n");
    assertEquals(Js.obj(), get(10_000).json());
    server.close();
    server = null;
  }

  @Test
  void knowsWhichJavasBoundAnAnswersHead() {
    // Before Java 17.0.11, 21.0.3 and 22.0.1, Java's own client reads a head of any size (the
    // build of a client refuses them); then jdk.http.maxHeaderSize, 384 KiB unless set.
    for (String version :
        List.of("17", "17.0.10", "17.0.10.1+3", "18.0.2", "19", "20.0.2", "21.0.2", "22")) {
      assertEquals(0, Http.javaHeadBound(Runtime.Version.parse(version), null), version);
    }
    for (String version :
        List.of(
            "17.0.11",
            "17.0.21+9",
            "21.0.3",
            "21.0.12.1+1-1-24.04.4-Ubuntu",
            "22.0.1",
            "23",
            "25.0.5+8")) {
      assertEquals(393_216, Http.javaHeadBound(Runtime.Version.parse(version), null), version);
    }
    Runtime.Version current = Runtime.Version.parse("21.0.13+9");
    assertEquals(0, Http.javaHeadBound(current, "0"));
    assertEquals(0, Http.javaHeadBound(current, "-1"));
    assertEquals(393_216, Http.javaHeadBound(current, "many"));
    assertEquals(1 << 20, Http.javaHeadBound(current, "1048576"));
    assertEquals(20L << 20, Http.javaHeadBound(current, "20971520"));
    assertTrue(Http.MAX_HEAD_BYTES == 10L << 20);
  }

  @Test
  void followsNoRedirectTheTargetNeverAsked() throws Exception {
    target = new RawServer((socket, request) -> RawServer.write(socket, "HTTP/1.1 200 OK\r\n\r\n"));
    for (int status : new int[] {301, 302, 303, 307, 308}) {
      server =
          new RawServer((socket, request) ->
              RawServer.write(
                  socket,
                  "HTTP/1.1 "
                      + status
                      + " Moved\r\nLocation: "
                      + target.url("http")
                      + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
      NetworkException error = failure(2000);
      assertTrue(
          error.getMessage().contains("answered a redirect (HTTP " + status + ")"),
          error.getMessage());
      assertTrue(error.isRetryable());
      server.close();
    }
    assertEquals(0, target.connections());
  }

  @Test
  void boundsTheWholeRequestBodyIncludedByTheTimeout() throws Exception {
    // The head at once, then the body never comes: the platform's own timeout stops at the head.
    server =
        new RawServer((socket, request) -> {
          RawServer.write(
              socket,
              "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\n{");
          Thread.sleep(30_000);
        });
    long started = System.nanoTime();
    NetworkException error = failure(300);
    long elapsed = System.nanoTime() - started;
    assertTrue(error.getMessage().contains("in time"), error.getMessage());
    assertTrue(elapsed >= 250_000_000L && elapsed < 3_000_000_000L, elapsed / 1_000_000 + " ms");
  }

  @Test
  void dropsABodyOfMoreThan1MiBAndTakesOneOfExactly1MiB() throws Exception {
    byte[] exact = concat(utf8("\""), utf8("a".repeat(MIB - 2)), utf8("\""));
    serve(200, exact);
    assertEquals(Js.of("a".repeat(MIB - 2)), get(5000).json());
    server.close();
    serve(200, concat(exact, utf8(" ")));
    NetworkException error = failure(5000);
    assertTrue(error.getMessage().contains("more than 1048576 bytes"), error.getMessage());
  }

  @Test
  void ignoresOneByteOrderMarkAndNoMore() throws Exception {
    serve(200, concat(BOM, utf8("{\"a\":1}")));
    assertEquals(Js.obj("a", 1), get(2000).json());
    server.close();
    serve(200, concat(BOM, BOM, utf8("{\"a\":1}")));
    assertNull(get(2000).json());
    // A byte order mark inside the JSON's strings is a character like any other.
    server.close();
    serve(200, concat(utf8("\""), BOM, utf8("x\"")));
    assertEquals(Js.of("﻿x"), get(2000).json());
  }

  @Test
  void readsABodyThatIsNotUtf8OrNotJsonAsNoJson() throws Exception {
    serve(200, new byte[] {0x22, (byte) 0xff, 0x22});
    Http.Answer answer = get(2000);
    assertEquals(200, answer.status());
    assertNull(answer.json());
    assertArrayEquals(new byte[] {0x22, (byte) 0xff, 0x22}, answer.body());
    server.close();
    // An overlong encoding, a lone surrogate's bytes: not UTF-8 either.
    serve(200, new byte[] {0x22, (byte) 0xc0, (byte) 0xaf, 0x22});
    assertNull(get(2000).json());
    server.close();
    serve(200, new byte[] {0x22, (byte) 0xed, (byte) 0xa0, (byte) 0x80, 0x22});
    assertNull(get(2000).json());
    server.close();
    serve(200, utf8("{\"a\":1,}"));
    assertNull(get(2000).json());
  }

  @Test
  void boundsTheResolutionOfTheNameByTheTimeoutWhateverTheClient() throws Exception {
    serve(200, utf8("{}"));
    // A client that resolves the name on the calling thread, for 3 s (nothing bounds a name's
    // resolution): the request returns at once, and fails in time.
    SlowClient slow = new SlowClient(client, 3000, false);
    long started = System.nanoTime();
    CompletableFuture<Http.Answer> answer =
        new Http(slow, 300).get(server.url("http") + "x", Map.of());
    long returned = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(returned < 200, "returned after " + returned + " ms");
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> answer.get(10, TimeUnit.SECONDS));
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    NetworkException network = assertInstanceOf(NetworkException.class, error.getCause());
    assertTrue(network.getMessage().contains("in time"), network.getMessage());
    assertTrue(elapsed >= 250 && elapsed < 2000, "failed after " + elapsed + " ms");
    // Cancelled while the name resolves: cancelled at once.
    CompletableFuture<Http.Answer> cancelled =
        new Http(slow, 5000).get(server.url("http") + "x", Map.of());
    long cancelling = System.nanoTime();
    assertTrue(cancelled.cancel(true));
    assertTrue(cancelled.isCancelled());
    assertTrue(System.nanoTime() - cancelling < 200_000_000L);
  }

  @Test
  void hasNoAnswerWhenTheConnectionFails() throws Exception {
    server = new RawServer((socket, request) -> socket.close());
    NetworkException error = failure(2000);
    assertTrue(error.getMessage().startsWith("No answer from 127.0.0.1"), error.getMessage());
  }

  @Test
  void readsABodyGivenInPartsBoundedAsWell() throws Exception {
    // checkout-node reads node-fetch's Node stream; here the platform's body subscriber is given
    // the body in parts of 16 KiB, and stops asking past 1 MiB.
    FakeHttp fake =
        new FakeHttp(uri -> FakeHttp.Answer.text(200, "\"" + "a".repeat(3 * MIB) + "\""));
    try {
      Http http = new Http(fake, 5000);
      ExecutionException error =
          assertThrows(
              ExecutionException.class,
              () -> http.get("https://breez.tips/x", Map.of()).get(10, TimeUnit.SECONDS));
      NetworkException network = assertInstanceOf(NetworkException.class, error.getCause());
      assertTrue(network.getMessage().contains("more than 1048576 bytes"), network.getMessage());
      // Dropped unread beyond: no more than 1 MiB and a part were delivered.
      assertTrue(fake.delivered.get() <= MIB + fake.partBytes, fake.delivered.get() + " bytes");
      FakeHttp small = new FakeHttp(uri -> FakeHttp.Answer.text(200, "{\"tag\":\"payRequest\"}"));
      try {
        small.partBytes = 5;
        assertEquals(
            Js.obj("tag", "payRequest"),
            new Http(small, 1000)
                .get("https://breez.tips/x", Map.of())
                .get(10, TimeUnit.SECONDS)
                .json());
      } finally {
        small.shutdown();
      }
    } finally {
      fake.shutdown();
    }
  }
}
