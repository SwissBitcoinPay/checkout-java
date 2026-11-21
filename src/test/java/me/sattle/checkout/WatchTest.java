package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.onchain.BitcoinNode;
import me.sattle.checkout.internal.onchain.CallExplorer;
import me.sattle.checkout.internal.onchain.Explorer;
import me.sattle.checkout.internal.onchain.Watch;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.Eventually;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The explorer's WebSocket while waitForPayment waits (SPEC.md §31, "Waiting for an on-chain
 * payment"): a lost socket connects again after 1 s, doubled at each failure up to 30 s, a
 * connection lost before it received a message being a failure; one that received a message sets
 * the delay back to 1 s. Each connection starts a full check: a server that accepts and drops every
 * connection must not have one every second. As checkout-node's watch tests, with sockets in memory
 * and delays of the test's (a timer never fires early: each delay is checked as a lower bound).
 */
class WatchTest {
  private static final String ADDRESS = Chain.depositAddress(1);

  /** A socket in memory, opened, answered and dropped by the test. */
  static final class MemorySocket implements WebSocketConnection {
    final WebSocketListener listener;
    final long openedAt = System.nanoTime();
    final List<String> sent = new CopyOnWriteArrayList<>();
    volatile boolean closed;

    MemorySocket(WebSocketListener listener) {
      this.listener = listener;
    }

    @Override
    public void send(String text) {
      sent.add(text);
    }

    @Override
    public void close() {
      closed = true;
    }

    void open() {
      listener.onOpen();
    }

    void message(Object message) {
      listener.onText(Js.write(Js.of(message)));
    }

    void drop() {
      listener.onClose(new IOException("dropped"));
    }
  }

  private final List<MemorySocket> sockets = new CopyOnWriteArrayList<>();
  private final List<URI> uris = new CopyOnWriteArrayList<>();
  private final AtomicInteger changes = new AtomicInteger();
  private Watch watcher;

  @BeforeEach
  void start() {
    sockets.clear();
  }

  @AfterEach
  void stop() {
    if (watcher != null) watcher.close();
  }

  private Watch watch(long firstDelayMs, long maxDelayMs) {
    return watch(CallExplorer.mempool(), firstDelayMs, maxDelayMs);
  }

  private Watch watch(CallExplorer explorer, long firstDelayMs, long maxDelayMs) {
    WebSocketFactory factory =
        (URI uri, Map<String, String> headers, Duration timeout, WebSocketListener listener) -> {
          uris.add(uri);
          MemorySocket socket = new MemorySocket(listener);
          sockets.add(socket);
          return socket;
        };
    watcher =
        Watch.watchAddress(
            factory,
            Duration.ofSeconds(10),
            explorer,
            ADDRESS,
            changes::incrementAndGet,
            firstDelayMs,
            maxDelayMs);
    return watcher;
  }

  /** The socket of the {@code n}th connection (from 1), once it was made. */
  private MemorySocket connection(int n) throws InterruptedException {
    Eventually.until(() -> sockets.size() >= n);
    return sockets.get(n - 1);
  }

  /** Milliseconds between the {@code n}th connection and the next one. */
  private long gapAfter(int n) {
    return TimeUnit.NANOSECONDS.toMillis(sockets.get(n).openedAt - sockets.get(n - 1).openedAt);
  }

  @Test
  void tracksTheAddressAndChecksAtEachConnection() throws Exception {
    watch(Constants.RECONNECT_FIRST_MS, Constants.RECONNECT_MAX_MS);
    MemorySocket socket = connection(1);
    assertEquals(List.of(URI.create(Constants.MEMPOOL_WEBSOCKET_URL)), uris);
    socket.open();
    Eventually.until(() -> changes.get() == 1);
    assertEquals(
        List.of(
            Js.obj("action", "want", "data", List.of("blocks")), Js.obj("track-address", ADDRESS)),
        socket.sent.stream().map(Js::parse).toList());
    socket.message(Js.obj("block", Js.obj("height", 970_001)));
    Eventually.until(() -> changes.get() == 2);
  }

  @Test
  void waitsLongerAtEachConnectionDroppedBeforeAMessageDoubledUpToTheLongest() throws Exception {
    watch(100, 800);
    List<Long> delays = List.of(100L, 200L, 400L, 800L, 800L);
    for (int n = 1; n <= delays.size(); n += 1) {
      MemorySocket socket = connection(n);
      socket.open();
      socket.drop();
    }
    connection(delays.size() + 1);
    for (int n = 1; n <= delays.size(); n += 1) {
      long gap = gapAfter(n);
      assertTrue(gap >= delays.get(n - 1), "connection " + (n + 1) + " after " + gap + " ms");
    }
    // Never doubled past the longest (1 600 ms at the last), on a loaded machine too.
    assertTrue(gapAfter(delays.size()) < 1600, gapAfter(delays.size()) + " ms");
    // One check per connection.
    Eventually.until(() -> changes.get() == delays.size());
  }

  @Test
  void waitsTheFirstDelayAgainOnceAConnectionReceivedAMessage() throws Exception {
    watch(300, 60_000);
    for (int n = 1; n <= 3; n += 1) {
      MemorySocket socket = connection(n);
      socket.open();
      socket.drop();
    }
    // 300, 600 and 1 200 ms: the next loss before a message would wait 2 400 ms.
    MemorySocket answered = connection(4);
    answered.open();
    answered.message(Js.obj("address-transactions", List.of()));
    Eventually.until(() -> changes.get() == 5);
    long dropped = System.nanoTime();
    answered.drop();
    long gap = TimeUnit.NANOSECONDS.toMillis(connection(5).openedAt - dropped);
    assertTrue(gapAfter(3) >= 1200, gapAfter(3) + " ms");
    assertTrue(gap >= 300 && gap < 2400, gap + " ms");
  }

  @Test
  void connectsNoMoreOnceClosed() throws Exception {
    watch(100, 800);
    MemorySocket socket = connection(1);
    socket.open();
    socket.drop();
    watcher.close();
    Thread.sleep(500);
    assertEquals(1, sockets.size());
  }

  // The merchant's Bitcoin node (SPEC.md §31): its WebSocket, with its fallback.

  private static final String NODE_URL = "http://192.168.1.10:3006";
  private static final URI NODE_WEBSOCKET = URI.create("ws://192.168.1.10:3006/api/v1/ws");
  private static final URI MEMPOOL_WEBSOCKET = URI.create(Constants.MEMPOOL_WEBSOCKET_URL);

  /** The clock of the call, in milliseconds. */
  private final long[] now = {1_791_381_280_000L};

  private CallExplorer nodeExplorer(BitcoinNode.Fallback fallback) {
    return new CallExplorer(
        new BitcoinNode(NODE_URL, fallback, false), new CallExplorer.Failures(), () -> now[0]);
  }

  private static List<String> names(List<Explorer.Source> sources) {
    return sources.stream().map(Explorer.Source::name).toList();
  }

  @Test
  void watchesTheAddressOnTheNodesWebSocketWhileItAnswers() throws Exception {
    CallExplorer explorer = nodeExplorer(BitcoinNode.Fallback.MEMPOOL);
    watch(explorer, 100, 800);
    MemorySocket socket = connection(1);
    socket.open();
    Eventually.until(() -> changes.get() == 1);
    assertEquals(
        List.of(
            Js.obj("action", "want", "data", List.of("blocks")), Js.obj("track-address", ADDRESS)),
        socket.sent.stream().map(Js::parse).toList());
    socket.message(Js.obj("block", Js.obj("height", 970_001)));
    Eventually.until(() -> changes.get() == 2);
    // Lost after a message: no failure of the node, its WebSocket again (after the first delay).
    socket.drop();
    connection(2);
    assertEquals(List.of(NODE_WEBSOCKET, NODE_WEBSOCKET), uris);
    assertEquals(List.of("node", "mempool"), names(explorer.sources()));
  }

  @Test
  void goesToMempoolSpacesWebSocketAtOnceWhenTheNodesIsLostBeforeAnyMessage() throws Exception {
    CallExplorer explorer = nodeExplorer(BitcoinNode.Fallback.MEMPOOL);
    // A first delay of a minute: only the fallback, at once, can connect again in this test.
    watch(explorer, 60_000, 60_000);
    MemorySocket node = connection(1);
    node.open();
    node.drop();
    MemorySocket mempool = connection(2);
    assertEquals(List.of(NODE_WEBSOCKET, MEMPOOL_WEBSOCKET), uris);
    // The node's failure is the call's: its reads ask mempool.space first for 60 s.
    assertEquals(List.of("mempool", "node"), names(explorer.sources()));
    mempool.open();
    mempool.message(Js.obj("address-transactions", List.of()));
    // One check per connection, one per message.
    Eventually.until(() -> changes.get() == 3);
    Thread.sleep(200);
    assertEquals(2, sockets.size());
  }

  @Test
  void goesToMempoolSpacesWebSocketAtOnceWhenTheNodesCannotBeOpened() throws Exception {
    CallExplorer explorer = nodeExplorer(BitcoinNode.Fallback.MEMPOOL);
    WebSocketFactory factory =
        (URI uri, Map<String, String> headers, Duration timeout, WebSocketListener listener) -> {
          uris.add(uri);
          if (uri.equals(NODE_WEBSOCKET)) throw new IllegalStateException("refused");
          MemorySocket socket = new MemorySocket(listener);
          sockets.add(socket);
          return socket;
        };
    watcher =
        Watch.watchAddress(
            factory,
            Duration.ofSeconds(10),
            explorer,
            ADDRESS,
            changes::incrementAndGet,
            60_000,
            60_000);
    connection(1).open();
    Eventually.until(() -> changes.get() == 1);
    assertEquals(List.of(NODE_WEBSOCKET, MEMPOOL_WEBSOCKET), uris);
  }

  @Test
  void triesTheNodeAfterMempoolSpaceWithin60SecondsOfItsFailure() throws Exception {
    CallExplorer explorer = nodeExplorer(BitcoinNode.Fallback.MEMPOOL);
    explorer.nodeFailed();
    now[0] += 59_000;
    watch(explorer, 60_000, 60_000);
    // mempool.space's first; lost before a message: the node's at once.
    MemorySocket mempool = connection(1);
    mempool.drop();
    MemorySocket node = connection(2);
    node.open();
    node.message(Js.obj("block", Js.obj("height", 970_001)));
    Eventually.until(() -> changes.get() == 2);
    assertEquals(List.of(MEMPOOL_WEBSOCKET, NODE_WEBSOCKET), uris);
  }

  @Test
  void neverConnectsToMempoolSpaceWithFallbackNoneButWaitsLongerAtEachFailureOfTheNode()
      throws Exception {
    CallExplorer explorer = nodeExplorer(BitcoinNode.Fallback.NONE);
    watch(explorer, 100, 800);
    List<Long> delays = List.of(100L, 200L, 400L);
    for (int n = 1; n <= delays.size(); n += 1) {
      MemorySocket socket = connection(n);
      socket.open();
      socket.drop();
    }
    connection(delays.size() + 1);
    for (int n = 1; n <= delays.size(); n += 1) {
      long gap = gapAfter(n);
      assertTrue(gap >= delays.get(n - 1), "connection " + (n + 1) + " after " + gap + " ms");
    }
    for (URI uri : uris) assertEquals(NODE_WEBSOCKET, uri);
  }

  @Test
  void waitsTheDelayAfterMempoolSpaceFailedTooThenTriesBothAgain() throws Exception {
    CallExplorer explorer = nodeExplorer(BitcoinNode.Fallback.MEMPOOL);
    watch(explorer, 300, 60_000);
    connection(1).drop();
    connection(2).drop();
    // Both failed: the next connection after the delay, mempool.space's first (the node failed
    // less than 60 s ago), then the node's at once.
    connection(3).drop();
    connection(4);
    assertTrue(gapAfter(2) >= 300, gapAfter(2) + " ms");
    assertEquals(
        List.of(NODE_WEBSOCKET, MEMPOOL_WEBSOCKET, MEMPOOL_WEBSOCKET, NODE_WEBSOCKET), uris);
  }

  /**
   * bitcoin-nodes.json's {@code reads}, as a WebSocket's connection (SPEC.md §31): one fails when
   * it cannot be opened or is lost before any message; the explorers it tries, in order, and the
   * one that answers ({@code network}: none, the next connection after the delay).
   */
  @TestFactory
  Stream<DynamicTest> readsAsAConnection() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(Js.spec("vectors/bitcoin-nodes.json"), "reads")) {
      tests.add(
          dynamicTest(
              Js.text(c, "name"),
              () -> {
                JsonValue node = Js.get(c, "node");
                // Each case its own call, clock and watcher.
                long[] clock = {1_791_381_280_000L};
                CallExplorer explorer =
                    new CallExplorer(
                        node == JsonNull.NULL
                            ? null
                            : new BitcoinNode(
                                NODE_URL, BitcoinNode.Fallback.of(Js.get(node, "fallback")), false),
                        new CallExplorer.Failures(),
                        () -> clock[0]);
                if (node != JsonNull.NULL && Js.bool(node, "failedRecently")) {
                  explorer.nodeFailed();
                  clock[0] += 59_000;
                }
                List<String> asked = new CopyOnWriteArrayList<>();
                List<String> answered = new CopyOnWriteArrayList<>();
                WebSocketFactory factory =
                    (URI uri,
                        Map<String, String> headers,
                        Duration timeout,
                        WebSocketListener listener) -> {
                      boolean isNode = uri.equals(NODE_WEBSOCKET);
                      String name = isNode ? "node" : "mempool";
                      asked.add(name);
                      boolean answers =
                          isNode ? Js.bool(node, "answers") : Js.bool(c, "mempoolAnswers");
                      if (answers) {
                        listener.onOpen();
                        listener.onText(Js.write(Js.obj("block", Js.obj("height", 970_001))));
                        answered.add(name);
                      } else {
                        listener.onClose(new IOException("lost before any message"));
                      }
                      return new MemorySocket(listener);
                    };
                Watch connection =
                    Watch.watchAddress(
                        factory,
                        Duration.ofSeconds(10),
                        explorer,
                        ADDRESS,
                        () -> {},
                        60_000,
                        60_000);
                try {
                  List<String> expected = Js.strings(Js.get(c, "expected"), "asked");
                  Eventually.until(() -> asked.size() >= expected.size());
                  // Nothing more: an answer stays, a failure waits a minute.
                  Thread.sleep(150);
                  assertEquals(expected, asked);
                  String result = Js.text(Js.get(c, "expected"), "result");
                  assertEquals(result.equals("network") ? List.of() : List.of(result), answered);
                } finally {
                  connection.close();
                }
              }));
    }
    return tests.stream();
  }

  /** End to end (the audit's): an explorer that accepts and drops each connection. */
  @Test
  void isConnectedToLessAndLessOftenByWaitForPaymentWhenEachConnectionIsDropped() throws Exception {
    TestMerchant merchant = new TestMerchant().start();
    try {
      merchant.publishChannel(
          merchant.channelValue(100, Map.of("relays", merchant.keyRelays)), 100);
      merchant.publishLot(List.of(ADDRESS), 91);
      AtomicInteger explorer = new AtomicInteger();
      WebSocketFactory factory =
          (URI uri, Map<String, String> headers, Duration timeout, WebSocketListener listener) -> {
            if (!uri.toString().equals(Constants.MEMPOOL_WEBSOCKET_URL)) {
              return merchant.net.open(uri, headers, timeout, listener);
            }
            explorer.incrementAndGet();
            // Accepted, then dropped before anything is sent.
            me.sattle.checkout.internal.Tasks.schedule(10, listener::onOpen);
            me.sattle.checkout.internal.Tasks.schedule(
                60, () -> listener.onClose(new IOException("dropped")));
            return new WebSocketConnection() {
              @Override
              public void send(String text) {}

              @Override
              public void close() {}
            };
          };
      SattlemeClient sattleme =
          merchant.clientBuilder().relayTimeoutMs(1000).webSocketFactory(factory).build();
      Invoice invoice = sattleme.createInvoice(Order.builder("W-1").sats(49_000).build());
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(invoice, WaitOptions.builder().intervalMs(60_000).build());
      // Connections at 0, 1 and 3 s, the next one at 7 s: never one a second (5 by 4.5 s).
      Thread.sleep(4_500);
      waiting.cancel(true);
      assertEquals(3, explorer.get());
    } finally {
      merchant.stop();
    }
  }
}
