package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.RelayNet;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The merchant's Bitcoin node end to end (SPEC.md §31, "The merchant's Bitcoin node"; PORTING.md
 * step 5), against local relays and fakes of breez.tips, of mempool.space and of the node (REST and
 * WebSocket, the same chain) and of BlockCypher: every read of an address and of the chain's
 * height, and waitForPayment's WebSocket, asked of the node and never of mempool.space while it
 * answers; its fallback when it does not ("mempool": mempool.space, the node asked after it for 60
 * s; "none": network, never expired); an invalid bitcoinNode reading mempool.space; the node in a
 * checkout link's record with payers. As checkout-node's bitcoin-node tests.
 */
class BitcoinNodeTest {
  private static final String ORIGIN = "https://pay.example";
  private static final List<String> LOT =
      List.of(Chain.depositAddress(1), Chain.depositAddress(2), Chain.depositAddress(3));
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** An Umbrel on the merchant's network, and a node of the Internet. */
  private static final String LOCAL_NODE = "http://192.168.1.10:3006";

  private static final String INTERNET_NODE = "https://mempool.shop.example";

  private TestMerchant merchant;

  @AfterEach
  void stop() {
    if (merchant != null) merchant.stop();
  }

  /** A merchant whose channel names {@code node} (absent when null), with a lot of 3 addresses. */
  private void start(JsonValue node) {
    merchant = new TestMerchant().start();
    Map<String, Object> fields = new HashMap<>();
    fields.put("relays", merchant.keyRelays);
    fields.put("checkout", Js.obj("origin", ORIGIN));
    if (node != null) fields.put("bitcoinNode", node);
    merchant.publishChannel(merchant.channelValue(100, fields), 100);
    merchant.publishLot(LOT, 90);
    // The node serves the same chain at <url>/api, when the channel's names one.
    JsonValue url = node instanceof JsonObject object ? object.get("url") : null;
    if (url != null) merchant.chain.nodeRest = Js.text(node, "url") + "/api";
  }

  private static JsonObject node(String url, String fallback, boolean payers) {
    return Js.obj("url", url, "fallback", fallback, "payers", payers);
  }

  private SattlemeClient client() {
    return merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  private static Order sats(String ref, long sats) {
    return Order.builder(ref).sats(sats).durationSeconds(600).build();
  }

  private static void until(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 500 && !condition.getAsBoolean(); i += 1) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  /** The explorers the requests since {@code from} asked, in order: "node" or "mempool". */
  private List<String> explorersAsked(int from) {
    List<String> names = new ArrayList<>();
    for (String href : merchant.chain.asked.subList(from, merchant.chain.asked.size())) {
      if (href.startsWith(Constants.MEMPOOL_URL + "/")) names.add("mempool");
      else if (!href.startsWith(Constants.BLOCKCYPHER_URL)) names.add("node");
    }
    return names;
  }

  /** Waits until no explorer was asked for 300 ms: the checks started so far are over. */
  private void quiet() throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    int size = -1;
    while (size != merchant.chain.asked.size() && System.nanoTime() < deadline) {
      size = merchant.chain.asked.size();
      Thread.sleep(300);
    }
  }

  private JsonObject pay(String address, String seed, long sats) {
    JsonObject tx = Chain.payment(Chain.txid(seed), address, sats);
    merchant.chain.add(address, tx);
    return tx;
  }

  @Nested
  class ANodeThatAnswers {
    @Test
    void isTheOnlyExplorerAskedLeasingCheckingAndConfirmingAPayment() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("N-1", 49_000));
      String address = invoice.onchainAddress();
      assertNotNull(address);
      // Leased once the node showed it unpaid: its transactions asked of the node.
      assertTrue(
          merchant.chain.nodeAsked().contains(LOCAL_NODE + "/api/address/" + address + "/txs"));
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
      JsonObject tx = pay(address, "paid", 49_000);
      String txid = Js.text(tx, "txid");
      assertEquals(
          new PaymentStatus.Unconfirmed("49000", 0, List.of(txid)), sattleme.getStatus(invoice));
      merchant.chain.confirm(address, txid, merchant.chain.height - 2);
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(txid)), sattleme.getStatus(invoice));
      // Never mempool.space; each check's two reads of the node.
      assertEquals(List.of(), merchant.chain.mempoolAsked());
      assertTrue(merchant.chain.nodeAsked().contains(LOCAL_NODE + "/api/blocks/tip/height"));
      // In plain http, with the User-Agent of every implementation (SPEC.md §15).
      for (FakeHttp.Call call : merchant.http.calls) {
        if (!call.uri().toString().startsWith(LOCAL_NODE)) continue;
        assertEquals(
            List.of(Constants.USER_AGENT), call.headers().allValues("User-Agent"), call.uri() + "");
      }
      // HTTP/1.1 in plain http: the platform's client would otherwise offer an upgrade to HTTP/2
      // (h2c) over it; https requests keep the client's own version.
      synchronized (merchant.http.requests) {
        for (java.net.http.HttpRequest request : merchant.http.requests) {
          boolean plain = request.uri().getScheme().equals("http");
          assertEquals(
              plain
                  ? java.util.Optional.of(java.net.http.HttpClient.Version.HTTP_1_1)
                  : java.util.Optional.empty(),
              request.version(),
              request.uri() + "");
        }
      }
    }

    @Test
    void followsNoRedirectOfTheNodeButReadsMempoolSpaceInsteadWithFallbackMempool()
        throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("N-2", 49_000));
      merchant.override =
          uri ->
              uri.toString().startsWith(LOCAL_NODE) ? new FakeHttp.Answer(301, new byte[0]) : null;
      int from = merchant.chain.asked.size();
      int calls = merchant.http.calls.size();
      assertEquals(
          new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
      // The node's redirect is a failure: the same read of mempool.space.
      assertEquals(List.of("mempool", "mempool"), explorersAsked(from));
      assertTrue(
          merchant.http.calls.subList(calls, merchant.http.calls.size()).stream()
              .anyMatch(call -> call.uri().toString().startsWith(LOCAL_NODE + "/api/")));
    }
  }

  @Nested
  class ANodeThatDoesNotAnswerWithFallbackMempool {
    @Test
    void leasesAndChecksFromMempoolSpaceInstead() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      merchant.chain.down.add("node");
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("M-1", 49_000));
      String address = invoice.onchainAddress();
      assertNotNull(address);
      JsonObject tx = pay(address, "paid", 49_000);
      merchant.chain.confirm(address, Js.text(tx, "txid"), merchant.chain.height - 2);
      int from = merchant.chain.asked.size();
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(Js.text(tx, "txid"))),
          sattleme.getStatus(invoice));
      // The node (both reads of one explorer, the node failing either; the other one's request
      // aborted, perhaps before it was sent), then both of mempool.space. The order of the
      // requests' arrival is the fake's: CallExplorer's is checked by the vectors.
      quiet();
      List<String> asked = explorersAsked(from);
      assertEquals(2, asked.stream().filter("mempool"::equals).count(), "" + asked);
      assertTrue(asked.contains("node"), "" + asked);
    }

    @Test
    void readsBothOfMempoolSpaceWhenTheNodeFailsOnlyTheHeight() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("M-2", 49_000));
      String address = invoice.onchainAddress();
      JsonObject tx = pay(address, "paid", 49_000);
      merchant.chain.confirm(address, Js.text(tx, "txid"), merchant.chain.height - 2);
      // The node lists no transaction, and fails the chain's height: its list is never combined
      // with mempool.space's height (that would leave the order open), both are read again of
      // mempool.space.
      merchant.override =
          uri -> {
            String href = uri.toString();
            if (href.equals(LOCAL_NODE + "/api/blocks/tip/height")) {
              return FakeHttp.Answer.text(500, "down");
            }
            return href.startsWith(LOCAL_NODE + "/api/address/")
                ? FakeHttp.Answer.json(Js.arr())
                : null;
          };
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(Js.text(tx, "txid"))),
          sattleme.getStatus(invoice));
      assertEquals(2, merchant.chain.mempoolAsked().size());
    }

    @Test
    void failsWithNetworkErrorWhenMempoolSpaceFailsToo() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("M-3", 49_000));
      merchant.chain.down.add("node");
      merchant.chain.down.add("explorer");
      NetworkException error =
          assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      assertTrue(
          error.getMessage().contains("Neither the merchant's Bitcoin node nor mempool.space"),
          error.getMessage());
    }

    @Test
    void asksTheNodeAfterMempoolSpaceFor60SecondsOnceItFailedInTheCall() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      RelayNet.ExplorerSocket socket = merchant.net.addServer("ws://192.168.1.10:3006/api/v1/ws");
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("M-4", 49_000));
      String address = invoice.onchainAddress();
      merchant.chain.down.add("node");
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      int from = merchant.chain.asked.size();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice, WaitOptions.builder().intervalMs(60_000).onStatus(statuses::add).build());
      until(() -> !statuses.isEmpty() && socket.messages.size() == 2);
      until(() -> explorersAsked(from).contains("mempool"));
      // The first check: the node, failing, then mempool.space.
      assertEquals("node", explorersAsked(from).get(0));
      // Within 60 s, in the same call: mempool.space first; it answers, the node is not asked.
      quiet();
      merchant.now += 30;
      int second = merchant.chain.asked.size();
      int seen = statuses.size();
      socket.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
      until(() -> statuses.size() > seen);
      quiet();
      List<String> within = explorersAsked(second);
      assertTrue(!within.isEmpty() && within.stream().allMatch("mempool"::equals), "" + within);
      // 60 s after its failure, and answering again: the node first again.
      merchant.chain.down.remove("node");
      merchant.now += 30;
      quiet();
      int third = merchant.chain.asked.size();
      int seenAgain = statuses.size();
      JsonObject tx = pay(address, "paid", 49_000);
      merchant.chain.confirm(address, Js.text(tx, "txid"), merchant.chain.height - 2);
      socket.push(Js.obj("address-transactions", List.of(tx)));
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(Js.text(tx, "txid"))),
          waiting.get(20, TimeUnit.SECONDS));
      assertTrue(statuses.size() > seenAgain);
      List<String> after = explorersAsked(third);
      assertTrue(!after.isEmpty() && after.stream().allMatch("node"::equals), "" + after);
    }

    @Test
    void asksTheNodeFirstAgainInEachCall() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("M-5", 49_000));
      merchant.chain.down.add("node");
      for (int call = 0; call < 2; call += 1) {
        int from = merchant.chain.asked.size();
        sattleme.getStatus(invoice);
        assertEquals("node", explorersAsked(from).get(0));
      }
    }

    @Test
    void watchesTheAddressOnMempoolSpacesWebSocketWhenTheNodesFails() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      // The node's WebSocket refuses every connection.
      RelayNet.ExplorerSocket mempool = merchant.startExplorerSocket();
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("M-6", 49_000));
      String address = invoice.onchainAddress();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(invoice, WaitOptions.builder().intervalMs(60_000).build());
      until(() -> mempool.messages.size() == 2);
      assertEquals(
          List.of(
              Js.obj("action", "want", "data", List.of("blocks")),
              Js.obj("track-address", address)),
          mempool.messages);
      assertEquals(1, (int) merchant.net.connections.get("ws://192.168.1.10:3006/api/v1/ws"));
      JsonObject tx = pay(address, "paid", 49_000);
      merchant.chain.confirm(address, Js.text(tx, "txid"), merchant.chain.height - 2);
      mempool.push(Js.obj("address-transactions", List.of(tx)));
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(Js.text(tx, "txid"))),
          waiting.get(20, TimeUnit.SECONDS));
    }
  }

  @Nested
  class ANodeThatDoesNotAnswerWithFallbackNone {
    @Test
    void leasesNoAddressLightningOnlyOrNetworkErrorOnchainOnly() throws Exception {
      start(node(LOCAL_NODE, "none", false));
      merchant.chain.down.add("node");
      SattlemeClient sattleme = client();
      assertNull(sattleme.createInvoice(sats("X-1", 49_000)).onchainAddress());
      merchant.pay = Js.with(merchant.pay, "maxSendable", 1_000_000);
      assertThrows(NetworkException.class, () -> sattleme.createInvoice(sats("X-2", 49_000)));
      assertEquals(List.of(), merchant.chain.mempoolAsked());
      assertTrue(!merchant.chain.nodeAsked().isEmpty());
    }

    @Test
    void givesNetworkNeverExpiredHoweverLongItIsDownThenPaidOnceItAnswers() throws Exception {
      start(node(LOCAL_NODE, "none", false));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("X-3", 49_000));
      String address = invoice.onchainAddress();
      assertNotNull(address);
      merchant.chain.down.add("node");
      // Paid while the lease runs; nobody sees it: the node does not answer.
      JsonObject tx = pay(address, "paid", 49_000);
      String txid = Js.text(tx, "txid");
      merchant.chain.received.put(
          txid, ISO.format(Instant.ofEpochSecond(invoice.expiresAt() - 60)));
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      // Long after the lease's end: still network, never expired nor underpaid for good.
      merchant.now = invoice.expiresAt() + 3600;
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      merchant.now = invoice.expiresAt() + 86_400;
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      assertEquals(List.of(), merchant.chain.mempoolAsked());
      // The node answers again: the payment, in time by BlockCypher's time, then 3 deep.
      merchant.chain.down.remove("node");
      assertEquals(
          new PaymentStatus.Unconfirmed("49000", 0, List.of(txid)), sattleme.getStatus(invoice));
      merchant.chain.confirm(address, txid, merchant.chain.height - 2);
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(txid)), sattleme.getStatus(invoice));
      assertEquals(List.of(), merchant.chain.mempoolAsked());
    }

    @Test
    void waitsOnTheNodesWebSocketAloneConnectingAgainUntilItAnswers() throws Exception {
      start(node(LOCAL_NODE, "none", false));
      RelayNet.ExplorerSocket mempool = merchant.startExplorerSocket();
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("X-4", 49_000));
      merchant.chain.down.add("node");
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice, WaitOptions.builder().intervalMs(250).onStatus(statuses::add).build());
      // The node's WebSocket refused, again after 1 s; mempool.space's never.
      until(
          () -> merchant.net.connections.getOrDefault("ws://192.168.1.10:3006/api/v1/ws", 0) >= 2);
      merchant.now = invoice.expiresAt() + 3600;
      Thread.sleep(500);
      assertEquals(0, mempool.connections());
      assertEquals(List.of(), merchant.chain.mempoolAsked());
      // Nothing judged on-chain: no status at all while the node does not answer.
      for (PaymentStatus status : statuses) {
        assertTrue(!status.isFinal(), status.state());
      }
      waiting.cancel(true);
    }
  }

  @Nested
  class TheNodesWebSocket {
    @Test
    void wakesWaitForPaymentWithTheNodesMessagesNeverMempoolSpaces() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      RelayNet.ExplorerSocket node = merchant.net.addServer("ws://192.168.1.10:3006/api/v1/ws");
      RelayNet.ExplorerSocket mempool = merchant.startExplorerSocket();
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(sats("S-1", 49_000));
      String address = invoice.onchainAddress();
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice, WaitOptions.builder().intervalMs(60_000).onStatus(statuses::add).build());
      until(() -> node.messages.size() == 2 && !statuses.isEmpty());
      assertEquals(
          List.of(
              Js.obj("action", "want", "data", List.of("blocks")),
              Js.obj("track-address", address)),
          node.messages);
      JsonObject tx = pay(address, "watched", 49_000);
      node.push(Js.obj("address-transactions", List.of(tx)));
      until(() -> statuses.get(statuses.size() - 1).state().equals("unconfirmed"));
      merchant.chain.confirm(address, Js.text(tx, "txid"), merchant.chain.height - 2);
      node.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
      assertEquals(
          new PaymentStatus.PaidOnchain("49000", List.of(Js.text(tx, "txid"))),
          waiting.get(20, TimeUnit.SECONDS));
      until(() -> node.clients() == 0);
      assertEquals(0, mempool.connections());
      assertEquals(List.of(), merchant.chain.mempoolAsked());
      // Its handshake carries the User-Agent, as the relays' does.
      assertTrue(
          merchant.net.handshakes.stream()
              .allMatch(headers -> Constants.USER_AGENT.equals(headers.get("User-Agent"))));
    }
  }

  @Nested
  class AnInvalidNode {
    @Test
    void readsMempoolSpaceTheChannelStillValid() throws Exception {
      for (JsonValue invalid :
          List.of(
              // http to a public host, a fallback of another spelling, payers absent or not a
              // boolean, payers true for a node that is not of the Internet.
              node("http://node.example", "mempool", false),
              Js.obj("url", LOCAL_NODE, "fallback", "Mempool", "payers", false),
              Js.obj("url", LOCAL_NODE, "fallback", "none"),
              Js.obj("url", LOCAL_NODE, "fallback", "none", "payers", "false"),
              node(LOCAL_NODE, "none", true))) {
        start(invalid);
        merchant.chain.nodeRest = Js.text(invalid, "url") + "/api";
        SattlemeClient sattleme = client();
        Invoice invoice = sattleme.createInvoice(sats("I-1", 49_000));
        assertNotNull(invoice.onchainAddress());
        assertEquals(
            new PaymentStatus.Open(invoice.expiresAt(), "0", null), sattleme.getStatus(invoice));
        assertEquals(List.of(), merchant.chain.nodeAsked(), Js.write(invalid));
        assertTrue(!merchant.chain.mempoolAsked().isEmpty());
        merchant.stop();
        merchant = null;
      }
    }
  }

  @Nested
  class ACheckoutLinksRecord {
    /** The record of the link at {@code url}, as its page opens it. */
    private JsonObject recordOf(String url) throws InterruptedException {
      Checkout.Link fragment =
          assertInstanceOf(
              Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
      String address = Derive.checkoutAddress(fragment.linkKey());
      merchant.net.settle();
      for (String relay : fragment.relays()) {
        for (JsonValue held : merchant.net.get(relay).events) {
          NostrEvent event = Events.checked(held);
          if (event == null
              || !event.pubkey().equals(fragment.author())
              || !address.equals(event.tag("d"))) {
            continue;
          }
          return (JsonObject)
              CheckoutVectorTest.openValue(
                  event, Derive.checkoutEncryptionKey(fragment.linkKey()), "checkout");
        }
      }
      throw new AssertionError("no record");
    }

    private Order withCheckout(String ref, long sats) {
      return Order.builder(ref)
          .sats(sats)
          .durationSeconds(600)
          .checkout(true)
          .redirectUrl("https://shop.example/thanks")
          .build();
    }

    @Test
    void holdsTheNodesUrlAndFallbackWithPayersNeverPayers() throws Exception {
      start(node(INTERNET_NODE, "none", true));
      SattlemeClient sattleme = client();
      Invoice invoice = sattleme.createInvoice(withCheckout("C-1", 49_000));
      assertNotNull(invoice.onchainAddress());
      JsonObject record = recordOf(invoice.checkoutUrl());
      assertEquals(2, Js.integer(record, "version"));
      assertEquals(Js.obj("url", INTERNET_NODE, "fallback", "none"), record.get("bitcoinNode"));
      assertEquals(
          new me.sattle.checkout.internal.onchain.BitcoinNode.RecordNode(
              INTERNET_NODE, me.sattle.checkout.internal.onchain.BitcoinNode.Fallback.NONE),
          Checkout.parseValue(record).bitcoinNode());
      // Made again by invoice_pending: with the node too.
      InvoicePendingException pending =
          assertThrows(
              InvoicePendingException.class,
              () -> sattleme.createInvoice(withCheckout("C-1", 49_000)));
      assertNotNull(pending.getCheckoutUrl());
      assertEquals(
          Js.obj("url", INTERNET_NODE, "fallback", "none"),
          recordOf(pending.getCheckoutUrl()).get("bitcoinNode"));
    }

    @Test
    void holdsNoNodeWithPayersFalseWithoutANodeOrWithoutAnAddress() throws Exception {
      start(node(LOCAL_NODE, "mempool", false));
      Invoice local = client().createInvoice(withCheckout("C-2", 49_000));
      JsonObject record = recordOf(local.checkoutUrl());
      assertEquals(2, Js.integer(record, "version"));
      assertTrue(!record.has("bitcoinNode"));
      merchant.stop();

      start(null);
      Invoice none = client().createInvoice(withCheckout("C-3", 49_000));
      assertTrue(!recordOf(none.checkoutUrl()).has("bitcoinNode"));
      merchant.stop();

      // Below the on-chain floor: no address, a record of version 1, never the node.
      start(node(INTERNET_NODE, "mempool", true));
      Invoice lightning = client().createInvoice(withCheckout("C-4", 900));
      assertNull(lightning.onchainAddress());
      JsonObject first = recordOf(lightning.checkoutUrl());
      assertEquals(1, Js.integer(first, "version"));
      assertTrue(!first.has("bitcoinNode"));
    }
  }
}
