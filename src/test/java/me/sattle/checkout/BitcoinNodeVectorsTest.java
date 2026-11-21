package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Channels;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.onchain.BitcoinNode;
import me.sattle.checkout.internal.onchain.CallExplorer;
import me.sattle.checkout.internal.onchain.Explorer;
import me.sattle.checkout.internal.onchain.NodeUrl;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The package against vectors/bitcoin-nodes.json (SPEC.md §31, "The merchant's Bitcoin node"; §30,
 * "The record"), each group through the package's own functions, as PORTING.md step 4 says: its
 * {@code urls} through the check of a node URL ({@link NodeUrl#parse}) and the URLs the node is
 * read at, its {@code channels} through the reading of the channel ({@link Channels#parseChannel}),
 * its {@code reads} through the explorer of a call ({@link CallExplorer}), which decides which
 * explorers a read asks, and in what order, its {@code writes} through the making of a checkout
 * link's record ({@link Checkout#checkoutValue}) and its {@code records} through the reading of a
 * record, as a page reads it ({@link Checkout#parseValue}).
 */
class BitcoinNodeVectorsTest {
  private static final JsonValue VECTORS = Js.spec("vectors/bitcoin-nodes.json");

  /** The channel of the app's vector: valid, without a {@code bitcoinNode}. */
  private static final JsonObject CHANNEL =
      (JsonObject) Js.get(Js.get(Js.get(Js.spec("vectors/app/app.json"), "channel"), "value"), "v");

  private static boolean has(JsonValue object, String name) {
    return ((JsonObject) object).has(name);
  }

  @TestFactory
  Stream<DynamicTest> urls() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(VECTORS, "urls")) {
      tests.add(
          dynamicTest(
              Js.text(c, "name"),
              () -> {
                NodeUrl node = NodeUrl.parse(Js.text(c, "url"));
                JsonValue expected = Js.get(c, "expected");
                if (expected == JsonNull.NULL) {
                  assertEquals(null, node);
                  return;
                }
                assertNotNull(node);
                assertEquals(Js.text(c, "url"), node.url());
                assertEquals(expected, Js.obj("rest", node.rest(), "webSocket", node.webSocket()));
              }));
    }
    return tests.stream();
  }

  /** {@code value} as the channel's {@code bitcoinNode}, absent when null. */
  private static Channels.Channel channelWith(JsonValue value) {
    JsonObject channel = (JsonObject) Js.parse(Js.write(CHANNEL));
    if (value != null) channel.put("bitcoinNode", value);
    return Channels.parseChannel(channel, Js.integer(CHANNEL, "updatedAt"));
  }

  @TestFactory
  Stream<DynamicTest> channels() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(VECTORS, "channels")) {
      tests.add(
          dynamicTest(
              Js.text(c, "name"),
              () -> {
                Channels.Channel channel = channelWith(has(c, "value") ? Js.get(c, "value") : null);
                // Valid either way.
                assertNotNull(channel);
                JsonValue expected = Js.get(c, "expected");
                BitcoinNode node = channel.bitcoinNode();
                if (expected == JsonNull.NULL) {
                  assertEquals(null, node);
                  return;
                }
                assertNotNull(node);
                assertEquals(
                    expected,
                    Js.obj(
                        "url",
                        node.url(),
                        "fallback",
                        node.fallback().json(),
                        "payers",
                        node.payers()));
              }));
    }
    return tests.stream();
  }

  /** The node of the reads: on the merchant's network. */
  private static final String NODE_URL = "http://192.168.1.10:3006";

  /**
   * One read of {@code c} (bitcoin-nodes.json, {@code reads}) through the package's explorer of a
   * call ({@link CallExplorer#read}): each explorer answers or fails as the case says; what it
   * asked, in order, and whose answer it gave.
   */
  private static JsonObject read(JsonValue c) throws InterruptedException {
    JsonValue node = Js.get(c, "node");
    boolean mempoolAnswers = Js.bool(c, "mempoolAnswers");
    // The clock of the call, in milliseconds: the node failed 59 s ago, or never.
    long[] now = {1_791_381_280_000L};
    CallExplorer.Failures failures = new CallExplorer.Failures();
    CallExplorer explorer =
        new CallExplorer(
            node == JsonNull.NULL
                ? null
                : new BitcoinNode(
                    NODE_URL, BitcoinNode.Fallback.of(Js.get(node, "fallback")), false),
            failures,
            () -> now[0]);
    if (node != JsonNull.NULL && Js.bool(node, "failedRecently")) {
      explorer.nodeFailed();
      now[0] += 59_000;
    }
    List<String> asked = new ArrayList<>();
    String result;
    try {
      result =
          explorer.read(source -> {
            asked.add(source.name());
            boolean answers = source.isNode() ? Js.bool(node, "answers") : mempoolAnswers;
            assertEquals(
                source.isNode() ? NODE_URL + "/api" : Constants.MEMPOOL_URL, source.rest());
            return answers
                ? CompletableFuture.completedFuture(source.name())
                : CompletableFuture.failedFuture(new NetworkException("No answer"));
          });
    } catch (NetworkException e) {
      result = "network";
    }
    return Js.obj("asked", JsonArray.ofStrings(asked), "result", result);
  }

  @TestFactory
  Stream<DynamicTest> reads() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(VECTORS, "reads")) {
      tests.add(
          dynamicTest(Js.text(c, "name"), () -> assertEquals(Js.get(c, "expected"), read(c))));
      // The order alone, as explorersToAsk gives it: what is asked when every explorer fails.
      tests.add(
          dynamicTest(
              "in order: " + Js.text(c, "name"),
              () -> {
                JsonValue node = Js.get(c, "node");
                List<String> order =
                    node == JsonNull.NULL
                        ? CallExplorer.explorersToAsk(null, false)
                        : CallExplorer.explorersToAsk(
                            BitcoinNode.Fallback.of(Js.get(node, "fallback")),
                            Js.bool(node, "failedRecently"));
                List<String> asked = Js.strings(Js.get(c, "expected"), "asked");
                assertEquals(asked, order.subList(0, asked.size()));
              }));
    }
    return tests.stream();
  }

  @Test
  void asksTheNodeFirstAgainOnce60SecondsPassedSinceItFailed() throws InterruptedException {
    long[] now = {1_000_000L};
    CallExplorer.Failures failures = new CallExplorer.Failures();
    BitcoinNode node = new BitcoinNode(NODE_URL, BitcoinNode.Fallback.MEMPOOL, false);
    CallExplorer explorer = new CallExplorer(node, failures, () -> now[0]);
    assertEquals(List.of("node", "mempool"), names(explorer.sources()));
    explorer.nodeFailed();
    now[0] += 59_999;
    assertEquals(List.of("mempool", "node"), names(explorer.sources()));
    // Another read of the same call (another channel read, the same node): its failure too.
    assertEquals(
        List.of("mempool", "node"),
        names(new CallExplorer(node, failures, () -> now[0]).sources()));
    // Another call: its own failures.
    assertEquals(
        List.of("node", "mempool"),
        names(new CallExplorer(node, new CallExplorer.Failures(), () -> now[0]).sources()));
    now[0] += 1;
    assertEquals(List.of("node", "mempool"), names(explorer.sources()));
  }

  @Test
  void neverAsksMempoolSpaceWithFallbackNoneHoweverOftenTheNodeFailed()
      throws InterruptedException {
    long[] now = {1_000_000L};
    CallExplorer explorer =
        new CallExplorer(
            new BitcoinNode(NODE_URL, BitcoinNode.Fallback.NONE, false),
            new CallExplorer.Failures(),
            () -> now[0]);
    for (int i = 0; i < 3; i += 1) {
      List<String> asked = new ArrayList<>();
      assertThrows(
          NetworkException.class,
          () ->
              explorer.read(source -> {
                asked.add(source.name());
                return CompletableFuture.failedFuture(new NetworkException("No answer"));
              }));
      assertEquals(List.of("node"), asked);
      now[0] += 1000;
    }
  }

  /** The node a page reads of a record, as the vectors write it (null: mempool.space). */
  private static JsonValue nodeOf(Checkout.CheckoutRecord record) {
    BitcoinNode.RecordNode node = record.bitcoinNode();
    return node == null
        ? JsonNull.NULL
        : Js.obj("url", node.url(), "fallback", node.fallback().json());
  }

  @TestFactory
  Stream<DynamicTest> records() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(VECTORS, "records")) {
      tests.add(
          dynamicTest(
              Js.text(c, "name"),
              () -> {
                JsonObject value = (JsonObject) Js.get(c, "value");
                Checkout.CheckoutRecord record = Checkout.parseValue(value);
                // Valid in every case.
                assertNotNull(record);
                assertEquals(Js.get(c, "expected"), nodeOf(record));
                // What else the page shows is the record's without the field.
                JsonObject without = new JsonObject();
                for (String name : value.names()) {
                  if (!name.equals("bitcoinNode")) without.put(name, value.get(name));
                }
                Checkout.CheckoutRecord plain = Checkout.parseValue(without);
                assertNotNull(plain);
                assertEquals(null, plain.bitcoinNode());
                assertEquals(
                    plain,
                    new Checkout.CheckoutRecord(
                        record.businessName(),
                        record.invoice(),
                        record.paymentHash(),
                        record.verifyUrl(),
                        record.onchainAddress(),
                        record.sats(),
                        record.createdAt(),
                        record.expiresAt(),
                        record.fiat(),
                        record.note(),
                        record.redirectUrl(),
                        record.redirectShown(),
                        record.rest(),
                        null));
              }));
    }
    return tests.stream();
  }

  /** A record of version 2 of the {@code records} vectors: what the writes make again. */
  private static final JsonObject RECORD =
      (JsonObject) Js.get(Js.list(VECTORS, "records").get(0), "value");

  @TestFactory
  Stream<DynamicTest> writes() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(VECTORS, "writes")) {
      tests.add(
          dynamicTest(
              Js.text(c, "name"),
              () -> {
                // The channel createInvoice read, and the record its link holds, as the client
                // makes it (Engine: the channel's node for the page, an address or not).
                BitcoinNode node =
                    channelWith(has(c, "channel") ? Js.get(c, "channel") : null).bitcoinNode();
                boolean address = Js.bool(c, "address");
                JsonObject value =
                    Checkout.checkoutValue(
                        Js.text(RECORD, "businessName"),
                        address ? null : "lnbc1",
                        address ? null : "https://breez.tips/lnurlp/verify/x",
                        address ? Js.text(RECORD, "onchainAddress") : null,
                        Js.integer(RECORD, "sats"),
                        Js.integer(RECORD, "createdAt"),
                        Js.integer(RECORD, "expiresAt"),
                        null,
                        Js.text(RECORD, "note"),
                        Js.text(RECORD, "redirectUrl"),
                        null,
                        BitcoinNode.recordNodeFor(node));
                assertEquals(address ? 2 : 1, Js.integer(value, "version"));
                JsonValue expected = Js.get(c, "expected");
                assertEquals(expected == JsonNull.NULL ? null : expected, value.get("bitcoinNode"));
                // As its page reads it.
                if (address) assertEquals(expected, nodeOf(Checkout.parseValue(value)));
              }));
    }
    return tests.stream();
  }

  private static List<String> names(List<Explorer.Source> sources) {
    return sources.stream().map(Explorer.Source::name).toList();
  }
}
