package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.RawServer;
import me.sattle.checkout.support.RelayNet;
import me.sattle.checkout.support.SlowClient;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The platform's WebSocket ({@link java.net.http.WebSocket}, behind {@link WebSocketFactory}) as
 * SPEC.md §13 and §15 ask it, against a server that speaks RFC 6455 by hand: the User-Agent of
 * every implementation in place of the platform's own, no redirect followed, no compression
 * offered, a message of more than 1 MiB refused whatever its parts, the handshake bounded; and
 * every handshake the package makes, to the relays and to the explorer, carries that User-Agent.
 */
class WebSocketTest {
  private static final Map<String, String> HEADERS = Map.of("User-Agent", Constants.USER_AGENT);
  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final WebSocketFactory sockets = WebSocketFactory.jdk(http);
  private RawServer server;
  private RawServer target;

  @AfterEach
  void stop() {
    if (server != null) server.close();
    if (target != null) target.close();
  }

  /** What a connection told its listener. */
  static final class Heard implements WebSocketListener {
    final CompletableFuture<Void> opened = new CompletableFuture<>();
    final CompletableFuture<Throwable> closed = new CompletableFuture<>();
    final List<String> messages = new CopyOnWriteArrayList<>();
    final CountDownLatch first = new CountDownLatch(1);

    @Override
    public void onOpen() {
      opened.complete(null);
    }

    @Override
    public CompletionStage<?> onText(String message) {
      messages.add(message);
      first.countDown();
      return null;
    }

    @Override
    public void onClose(Throwable error) {
      closed.complete(error);
    }
  }

  private Heard open(String url, Duration handshake) {
    Heard heard = new Heard();
    WebSocketConnection connection = sockets.open(URI.create(url), HEADERS, handshake, heard);
    assertNotNull(connection);
    return heard;
  }

  private Heard open(String url) {
    return open(url, Duration.ofSeconds(5));
  }

  @Test
  void sendsTheUserAgentOfEveryRequestInPlaceOfThePlatformsOwn() throws Exception {
    server =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(socket, request, "");
          RawServer.drain(socket);
        });
    Heard heard = open(server.url("ws"));
    heard.opened.get(5, TimeUnit.SECONDS);
    RawServer.Request request = server.requests.get(0);
    // Exactly one: the platform's "Java-http-client/<version>" replaced, never sent beside it.
    assertEquals(List.of(Constants.USER_AGENT), request.all("User-Agent"));
    for (String[] header : request.headers()) {
      assertFalse(header[1].contains("Java-http-client"), header[0] + ": " + header[1]);
    }
  }

  @Test
  void offersNoCompressionAndRefusesAnExtensionNobodyAskedFor() throws Exception {
    server =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(socket, request, "");
          RawServer.drain(socket);
        });
    Heard heard = open(server.url("ws"));
    heard.opened.get(5, TimeUnit.SECONDS);
    assertEquals(List.of(), server.requests.get(0).all("Sec-WebSocket-Extensions"));
    server.close();
    // A server that answers with permessage-deflate anyway: no connection.
    server =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(
              socket, request, "Sec-WebSocket-Extensions: permessage-deflate\r\n");
          RawServer.drain(socket);
        });
    Heard refused = open(server.url("ws"));
    assertNotNull(refused.closed.get(5, TimeUnit.SECONDS));
    assertFalse(refused.opened.isDone());
  }

  @Test
  void neverFollowsARedirectOfTheHandshakeAFailedConnection() throws Exception {
    // Where the redirect points: a server that would answer.
    target =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(socket, request, "");
          RawServer.drain(socket);
        });
    server =
        new RawServer((socket, request) ->
            RawServer.write(
                socket,
                "HTTP/1.1 302 Found\r\nLocation: "
                    + target.url("ws")
                    + "\r\nContent-Length: 0\r\n\r\n"));
    long started = System.nanoTime();
    Heard heard = open(server.url("ws"), Duration.ofSeconds(3));
    Throwable error = heard.closed.get(5, TimeUnit.SECONDS);
    assertNotNull(error);
    // Failed at the redirect, not at the timeout; the target never asked.
    assertTrue(System.nanoTime() - started < 2_000_000_000L);
    assertFalse(heard.opened.isDone());
    assertEquals(0, target.connections());
  }

  @Test
  void holdsNoMoreThanTheBoundOfTheHandshakesAnswerAHeaderOf11MibIsNoConnection() throws Exception {
    // SPEC.md §15 (checkout-spec 1c88066): what the client holds of the answer to the opening
    // handshake is bounded too, 10 MiB at most: a header of 11 MiB fails the connection at once
    // (Java's own bound, 384 KiB), and the relay's read is no answer (network).
    String big = "X-Big: " + "a".repeat(11 << 20) + "\r\n";
    server =
        new RawServer((socket, request) -> {
          try {
            RawServer.acceptWebSocket(socket, request, big);
            RawServer.drain(socket);
          } catch (java.io.IOException e) {
            // The client hung up.
          }
        });
    long started = System.nanoTime();
    Heard heard = open(server.url("ws"), Duration.ofSeconds(10));
    assertNotNull(heard.closed.get(10, TimeUnit.SECONDS));
    assertFalse(heard.opened.isDone());
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    // A relay that answers so: the read fails before its timeout, no answer.
    WebSocketFactory toServer =
        (uri, headers, timeout, listener) ->
            sockets.open(URI.create(server.url("ws")), headers, timeout, listener);
    me.sattle.checkout.internal.nostr.RelayPool pool =
        new me.sattle.checkout.internal.nostr.RelayPool(
            toServer,
            10_000,
            new me.sattle.checkout.internal.RandomBytes(new java.security.SecureRandom()),
            () -> 1_791_378_000L);
    try {
      started = System.nanoTime();
      me.sattle.checkout.internal.nostr.RelayPool.Answers read =
          pool.queryRelays(
                  List.of("wss://big-head.test"),
                  me.sattle.checkout.support.Js.obj("kinds", List.of(30078)),
                  new byte[32],
                  1,
                  false,
                  value -> value,
                  false)
              .get(10, TimeUnit.SECONDS);
      assertFalse(read.outcome().answered());
      assertTrue(System.nanoTime() - started < 5_000_000_000L);
    } finally {
      pool.close();
    }
  }

  @Test
  void boundsTheHandshakeByTheRelayTimeout() throws Exception {
    // A server that takes the connection and never answers the handshake.
    server = new RawServer((socket, request) -> Thread.sleep(30_000));
    long started = System.nanoTime();
    Heard heard = open(server.url("ws"), Duration.ofMillis(300));
    assertNotNull(heard.closed.get(5, TimeUnit.SECONDS));
    long elapsed = System.nanoTime() - started;
    assertTrue(elapsed >= 250_000_000L, "after " + elapsed / 1_000_000 + " ms");
    assertTrue(elapsed < 3_000_000_000L, "after " + elapsed / 1_000_000 + " ms");
    assertFalse(heard.opened.isDone());
  }

  @Test
  void boundsTheResolutionOfTheNameByTheHandshakeTimeoutWhateverTheClient() throws Exception {
    server =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(socket, request, "");
          RawServer.drain(socket);
        });
    // A client that resolves the name on the calling thread, for 1.5 s: the connection is
    // returned at once, and fails in time; it is never told open.
    WebSocketFactory slow = WebSocketFactory.jdk(new SlowClient(http, 1500, false));
    long started = System.nanoTime();
    Heard heard = new Heard();
    assertNotNull(slow.open(URI.create(server.url("ws")), HEADERS, Duration.ofMillis(300), heard));
    long returned = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(returned < 200, "returned after " + returned + " ms");
    assertNotNull(heard.closed.get(5, TimeUnit.SECONDS));
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 250 && elapsed < 1400, "failed after " + elapsed + " ms");
    Thread.sleep(2000);
    assertFalse(heard.opened.isDone());
    // A resolution on the client's own threads that never ends (the platform's own connect
    // timeout cannot cut it short): failed in time too.
    WebSocketFactory hanging = WebSocketFactory.jdk(new SlowClient(http, 0, true));
    started = System.nanoTime();
    Heard stuck = new Heard();
    hanging.open(URI.create(server.url("ws")), HEADERS, Duration.ofMillis(300), stuck);
    assertNotNull(stuck.closed.get(5, TimeUnit.SECONDS));
    elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 250 && elapsed < 2000, "failed after " + elapsed + " ms");
    assertFalse(stuck.opened.isDone());
  }

  /** A server that sends one message in {@code parts}, then waits for the client's close. */
  private Heard receive(String... parts) throws Exception {
    server =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(socket, request, "");
          RawServer.text(socket, parts);
          RawServer.text(socket, "after");
          RawServer.drain(socket);
        });
    Heard heard = open(server.url("ws"));
    heard.opened.get(5, TimeUnit.SECONDS);
    return heard;
  }

  @Test
  void takesAMessageOfExactly1MiBWhateverItsPartsAndCharacters() throws Exception {
    int half = Constants.MAX_RELAY_MESSAGE_BYTES / 2;
    String a = "a".repeat(half);
    Heard ascii = receive(a, a);
    assertTrue(ascii.first.await(5, TimeUnit.SECONDS));
    assertEquals(Constants.MAX_RELAY_MESSAGE_BYTES, ascii.messages.get(0).length());
    server.close();
    // 2 bytes each in UTF-8: 524 288 of them are 1 MiB.
    String e = "é".repeat(half / 2);
    Heard twoBytes = receive(e, e);
    assertTrue(twoBytes.first.await(5, TimeUnit.SECONDS));
    assertEquals(half, twoBytes.messages.get(0).length());
    server.close();
    // 4 bytes each, a surrogate pair in Java: 262 144 of them; parts split anywhere.
    String emoji = "😀".repeat(half / 4);
    Heard fourBytes = receive(emoji, emoji);
    assertTrue(fourBytes.first.await(5, TimeUnit.SECONDS));
    assertEquals(half, fourBytes.messages.get(0).length());
    assertNull(fourBytes.closed.getNow(null));
  }

  @Test
  void refusesAMessageOfMoreThan1MiBInPartsTheConnectionEnded() throws Exception {
    int half = Constants.MAX_RELAY_MESSAGE_BYTES / 2;
    for (String[] parts :
        List.of(
            new String[] {"a".repeat(half), "a".repeat(half + 1)},
            new String[] {"é".repeat(half / 2), "é".repeat(half / 2), "é"},
            new String[] {"😀".repeat(half / 4), "😀".repeat(half / 4), "😀"},
            new String[] {"a".repeat(3 * half)})) {
      Heard heard = receive(parts);
      assertNotNull(heard.closed.get(5, TimeUnit.SECONDS));
      // Neither that message nor the next one.
      assertEquals(List.of(), heard.messages);
      server.close();
    }
  }

  @Test
  void sendsAndClosesInOrder() throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    server =
        new RawServer((socket, request) -> {
          RawServer.acceptWebSocket(socket, request, "");
          RawServer.readClient(socket, received);
          done.countDown();
        });
    Heard heard = new Heard();
    WebSocketConnection connection =
        sockets.open(URI.create(server.url("ws")), HEADERS, Duration.ofSeconds(5), heard);
    heard.opened.get(5, TimeUnit.SECONDS);
    for (int i = 0; i < 50; i += 1) connection.send("message " + i);
    connection.close();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    for (int i = 0; i < 50; i += 1) assertEquals("message " + i, received.get(i));
    // A close by the package is never told back.
    Thread.sleep(100);
    assertFalse(heard.closed.isDone());
  }

  @Test
  void sendsTheUserAgentInEveryHandshakeOfThePackageToTheRelaysAndToTheExplorer() throws Exception {
    TestMerchant merchant = new TestMerchant().start();
    try {
      RelayNet.ExplorerSocket explorer = merchant.startExplorerSocket();
      merchant.publishChannel(merchant.channelValue(100), 100);
      String address = Chain.depositAddress(1);
      merchant.publishLot(List.of(address), 90);
      SattlemeClient sattleme = merchant.client();
      Invoice invoice = sattleme.createInvoice(Order.builder("UA-1").sats(49_000).build());
      merchant.payInvoice(invoice.paymentHash());
      sattleme.waitForPayment(invoice, WaitOptions.builder().intervalMs(250).build());
      assertTrue(explorer.connections() >= 1);
      assertTrue(merchant.net.connections.keySet().contains(Constants.MEMPOOL_WEBSOCKET_URL));
      assertTrue(merchant.net.handshakes.size() >= merchant.keyRelays.size() + 1);
      for (Map<String, String> headers : merchant.net.handshakes) {
        assertEquals(Map.of("User-Agent", "sattleme-Checkout/1"), headers);
      }
      assertEquals("sattleme-Checkout/1", Constants.USER_AGENT);
    } finally {
      merchant.stop();
    }
  }
}
