package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Model;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Rests;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The package's half of the cross-check with the merchant's web app for the rest of an on-chain
 * underpayment (vectors/app/checkout-rest.json, SPEC.md §31; PORTING.md, "Definition of done", 3):
 * a checkout link to an invoice with an address, then a payment check that sees 20 000 of its 49
 * 000 sats on-chain and asks an invoice of the rest. Everything deterministic equals the vector
 * (the rest key, the rest author, the rest encryption key, the rest record address, the link's
 * value with restAuthor and restKey, the rest record's value); the events are this package's own,
 * valid, and open with those keys; and the vector's events open here.
 */
class CheckoutRestVectorTest {
  private static final JsonValue VECTOR = Js.spec("vectors/app/checkout-rest.json");
  private static final JsonValue LINK = Js.get(VECTOR, "link");
  private static final JsonValue REST = Js.get(VECTOR, "rest");
  private static final String ADDRESS = Chain.depositAddress("checkout-rest");

  private TestMerchant merchant;

  @AfterEach
  void stop() {
    if (merchant != null) merchant.stop();
  }

  @Test
  void theVectorsEventsOpenAsTheWebAppOpensThem() {
    assertEquals(ADDRESS, Js.text(VECTOR, "onchainAddress"));
    byte[] linkKey = Js.hex(LINK, "linkKey");
    String url = Js.text(LINK, "url");
    Checkout.Link link =
        assertInstanceOf(
            Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
    assertEquals(Js.text(LINK, "author"), link.author());
    assertEquals(Js.text(LINK, "address"), Derive.checkoutAddress(linkKey));
    assertEquals(Js.text(LINK, "encryptionKey"), Hex.encode(Derive.checkoutEncryptionKey(linkKey)));
    Records.Opened<Checkout.CheckoutRecord> opened =
        Records.open(
            Js.get(LINK, "event"),
            Constants.CHECKOUT_RECORD_TYPE,
            Derive.checkoutEncryptionKey(linkKey),
            Derive.checkoutAddress(linkKey),
            link.author(),
            (value, event) -> Checkout.parseValue(value));
    assertNotNull(opened);
    Checkout.CheckoutRecord record = opened.value();
    assertEquals(Js.text(REST, "restAuthor"), record.rest().author());
    assertEquals(Js.text(REST, "restKey"), Hex.encode(record.rest().key()));
    assertEquals(ADDRESS, record.onchainAddress());
    assertEquals(49_000, record.sats());
    // The rest record, as the page reads it with the rest author and key of the link.
    Derive.RestKeys keys = Derive.RestKeys.reader(record.rest().key(), record.rest().author());
    assertEquals(Js.text(REST, "encryptionKey"), Hex.encode(keys.encryptionKey()));
    Rests.OpenedRest rest =
        Rests.openRestRecord(
            Js.get(REST, "event"), keys, record.sats(), record.createdAt(), record.expiresAt());
    assertNotNull(rest);
    assertEquals(Js.text(REST, "invoice"), rest.invoice().bolt11());
    assertEquals(29_000, rest.invoice().sats());
    assertEquals(Js.text(REST, "paymentHash"), rest.invoice().paymentHash());
    assertEquals(
        List.of(
            List.of("d", Js.text(REST, "address")),
            List.of("expiration", Long.toString(Js.integer(VECTOR, "expiresAt") + 604_800))),
        rest.event().tags());
    assertEquals(
        Js.get(REST, "value"),
        CheckoutVectorTest.openValue(rest.event(), keys.encryptionKey(), "checkout-rest"));
  }

  @Test
  void makesTheVectorsLinkAndRestRecordEndToEnd() throws Exception {
    merchant = new TestMerchant();
    merchant.keyRelays = Js.strings(VECTOR, "relays");
    merchant.now = 1_791_382_000L;
    merchant.pay = (JsonObject) Js.get(VECTOR, "payRequest");
    merchant.start();
    merchant.publishChannel(
        merchant.channelValue(
            merchant.now - 60, Map.of("checkout", Js.obj("origin", Js.text(VECTOR, "origin")))),
        merchant.now - 60);
    merchant.publishLot(List.of(ADDRESS), merchant.now - 30);
    assertEquals(Js.text(VECTOR, "integrationKey"), Hex.encode(merchant.integrationKey));
    JsonValue order = Js.get(VECTOR, "order");
    SattlemeClient sattleme =
        merchant.clientBuilder().random(new FixedRandom("checkout-rest-vector")).build();
    Invoice invoice =
        sattleme.createInvoice(
            Order.builder(Js.text(order, "ref"))
                .sats(Js.text(order, "sats"))
                .note(Js.text(order, "note"))
                .durationSeconds((int) Js.integer(order, "durationSeconds"))
                .checkout(Js.bool(order, "checkout"))
                .redirectUrl(Js.text(order, "redirectUrl"))
                .build());
    assertEquals(Js.text(VECTOR, "onchainAddress"), invoice.onchainAddress());
    assertEquals(Js.text(VECTOR, "sats"), invoice.sats());
    assertEquals(Js.integer(VECTOR, "createdAt"), invoice.createdAt());
    assertEquals(Js.integer(VECTOR, "expiresAt"), invoice.expiresAt());

    // The link: its value equals the vector's, restAuthor and restKey included.
    String url = invoice.checkoutUrl();
    Checkout.Link link =
        assertInstanceOf(
            Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
    assertEquals(Js.strings(VECTOR, "relays"), link.relays());
    NostrEvent linkEvent = eventOf(link.author());
    assertNotNull(Events.checked(linkEvent.toJson()));
    assertEquals(
        List.of(
            List.of("d", Derive.checkoutAddress(link.linkKey())),
            List.of("expiration", Long.toString(invoice.expiresAt() + 604_800))),
        linkEvent.tags());
    JsonValue value =
        CheckoutVectorTest.openValue(
            linkEvent, Derive.checkoutEncryptionKey(link.linkKey()), "checkout");
    assertEquals(Js.get(LINK, "value"), value);
    assertEquals(Js.write(Js.get(LINK, "value")), Js.write(value));

    // 20 000 sats in the mempool, a minute later: the check asks an invoice of the 29 000 left.
    merchant.now += 60;
    JsonObject tx = Chain.payment(Chain.txid("checkout-rest"), ADDRESS, 20_000);
    assertEquals(Js.text(Js.get(VECTOR, "transaction"), "txid"), Js.text(tx, "txid"));
    merchant.chain.add(ADDRESS, tx);
    PaymentStatus status = sattleme.getStatus(invoice);
    PaymentStatus.Open open = assertInstanceOf(PaymentStatus.Open.class, status);
    assertEquals(Js.get(VECTOR, "status"), Model.status(open));

    // The rest keys and the rest record: equal to the vector's.
    Derive.RestKeys keys =
        Derive.RestKeys.of(
            merchant.integrationKey, Js.text(order, "ref"), ADDRESS, invoice.expiresAt());
    assertEquals(Js.text(REST, "restKey"), Hex.encode(keys.key()));
    assertEquals(Js.text(REST, "restAuthor"), keys.author());
    assertEquals(Js.text(REST, "encryptionKey"), Hex.encode(keys.encryptionKey()));
    String paymentHash = Js.text(REST, "paymentHash");
    assertEquals(Js.text(REST, "address"), Derive.restRecordAddress(keys.key(), paymentHash));
    NostrEvent restEvent = eventOf(keys.author());
    assertNotNull(Events.checked(restEvent.toJson()));
    assertEquals(
        List.of(
            List.of("d", Js.text(REST, "address")),
            List.of("expiration", Long.toString(invoice.expiresAt() + 604_800))),
        restEvent.tags());
    JsonValue restValue =
        CheckoutVectorTest.openValue(restEvent, keys.encryptionKey(), "checkout-rest");
    assertEquals(Js.get(REST, "value"), restValue);
    assertEquals(Js.write(Js.get(REST, "value")), Js.write(restValue));
    // The web app reads it, with what the link's record carries.
    Rests.OpenedRest opened =
        Rests.openRestRecord(
            restEvent.toJson(),
            Derive.RestKeys.reader(keys.key(), keys.author()),
            Long.parseLong(invoice.sats()),
            invoice.createdAt(),
            invoice.expiresAt());
    assertNotNull(opened);
    assertEquals(Js.text(REST, "invoice"), opened.invoice().bolt11());
    assertEquals(29_000, opened.invoice().sats());
    assertEquals(
        new PaymentStatus.Rest(
            "29000",
            Js.text(REST, "invoice"),
            "bitcoin:" + ADDRESS + "?amount=0.00029&lightning=" + Js.text(REST, "invoice")),
        open.rest());
  }

  private NostrEvent eventOf(String author) {
    return merchant.net.get("wss://relay.example").received.stream()
        .filter(received -> received.pubkey().equals(author))
        .findFirst()
        .orElseThrow();
  }
}
