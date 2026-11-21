package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Relays;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The package's half of the cross-check with the merchant's web app for checkout links
 * (vectors/app/checkout-link.json, SPEC.md §30; PORTING.md, "Definition of done", 2): for the
 * vector's order, channel, relays, pay request, price and invoice, the link this package makes end
 * to end holds the vector's record value exactly, in a valid event of its own author that opens
 * with the key its URL gives; and the vector's own event opens here as the web app opens it.
 */
class CheckoutVectorTest {
  private static final JsonValue VECTOR = Js.spec("vectors/app/checkout-link.json");
  private static final String ORIGIN = Js.text(VECTOR, "origin");

  private TestMerchant merchant;

  @AfterEach
  void stop() {
    if (merchant != null) merchant.stop();
  }

  /** The decrypted plaintext of a record, checked padded, and its value. */
  static JsonValue openValue(NostrEvent event, byte[] encryptionKey, String type) {
    String plaintext = DeclarationTest.decrypt(event.content(), encryptionKey);
    assertEquals(0, Text.utf8Length(plaintext) % Constants.RECORD_BLOCK_BYTES);
    JsonValue envelope = Js.parse(plaintext);
    assertEquals(type, Js.text(envelope, "t"));
    return Js.get(envelope, "v");
  }

  @Test
  void opensTheVectorsLinkAsTheWebAppDoes() {
    String url = Js.text(VECTOR, "url");
    Checkout.Fragment fragment = Checkout.parseFragment(url.substring(url.indexOf('#')));
    Checkout.Link link = assertInstanceOf(Checkout.Link.class, fragment);
    assertEquals(Js.text(VECTOR, "author"), link.author());
    assertEquals(Js.text(VECTOR, "linkKey"), Hex.encode(link.linkKey()));
    assertEquals(Js.strings(VECTOR, "relays"), link.relays());
    assertEquals(Js.text(VECTOR, "address"), Derive.checkoutAddress(link.linkKey()));
    assertEquals(
        Js.text(VECTOR, "encryptionKey"), Hex.encode(Derive.checkoutEncryptionKey(link.linkKey())));
    Records.Opened<Checkout.CheckoutRecord> opened =
        Records.open(
            Js.get(VECTOR, "event"),
            Constants.CHECKOUT_RECORD_TYPE,
            Derive.checkoutEncryptionKey(link.linkKey()),
            Derive.checkoutAddress(link.linkKey()),
            link.author(),
            (value, event) -> Checkout.parseValue(value));
    assertNotNull(opened);
    Checkout.CheckoutRecord record = opened.value();
    assertNotNull(record);
    // 0.02 CHF at 98 000 CHF/BTC: ⌈2 × 10^8 / 9 800 000⌉ = ⌈20.4…⌉ = 21 sats.
    assertEquals(21, record.sats());
    assertEquals("shop.example", record.redirectShown());
    assertEquals(Js.text(VECTOR, "invoice"), record.invoice());
    assertEquals(Js.text(VECTOR, "paymentHash"), record.paymentHash());
    assertEquals(Js.integer(VECTOR, "expiresAt"), record.expiresAt());
    assertEquals(
        Js.get(VECTOR, "value"),
        openValue(opened.event(), Derive.checkoutEncryptionKey(link.linkKey()), "checkout"));
    assertEquals(
        List.of(
            List.of("d", Js.text(VECTOR, "address")),
            List.of("expiration", Long.toString(Js.integer(VECTOR, "expiresAt") + 604_800))),
        opened.event().tags());
  }

  @Test
  void makesALinkHoldingTheVectorsRecordEndToEnd() throws Exception {
    merchant = new TestMerchant();
    merchant.keyRelays = Js.strings(VECTOR, "relays");
    merchant.now = 1_791_382_000L;
    // What the merchant's Lightning address answers (SPEC.md §16): its bounds, its metadata.
    merchant.pay = (JsonObject) Js.get(VECTOR, "payRequest");
    merchant.start();
    JsonObject channel =
        merchant.channelValue(merchant.now - 60, Map.of("checkout", Js.obj("origin", ORIGIN)));
    // The business's name of the vector's channel.
    ((JsonObject) Js.get(channel, "profile")).put("businessName", Js.text(VECTOR, "businessName"));
    merchant.publishChannel(channel, merchant.now - 60);
    JsonValue order = Js.get(VECTOR, "order");
    JsonValue fiat = Js.get(order, "fiat");
    String price = Js.text(VECTOR, "price");
    SattlemeClient sattleme =
        merchant
            .clientBuilder()
            .random(new FixedRandom("checkout-vector"))
            .rateSource(RateSource.custom(currency -> RateQuote.of(price)))
            .build();
    Invoice invoice =
        sattleme.createInvoice(
            Order.builder(Js.text(order, "ref"))
                .fiat(Js.text(fiat, "amount"), Js.text(fiat, "currency"))
                .note(Js.text(order, "note"))
                .durationSeconds((int) Js.integer(order, "durationSeconds"))
                .checkout(Js.bool(order, "checkout"))
                .redirectUrl(Js.text(order, "redirectUrl"))
                .build());
    // The vector's invoice: the same order, pay request, price and clock.
    assertEquals(Js.text(VECTOR, "sats"), invoice.sats());
    assertEquals(Js.text(VECTOR, "invoice"), invoice.bolt11());
    assertEquals(Js.text(VECTOR, "verifyUrl"), invoice.verifyUrl());
    assertEquals(Js.text(VECTOR, "paymentHash"), invoice.paymentHash());
    assertEquals(Js.integer(VECTOR, "expiresAt"), invoice.expiresAt());

    // Its URL: <origin>/pay#c.<author>.<key>.<relays>, with the vector's relays.
    String url = invoice.checkoutUrl();
    String vectorUrl = Js.text(VECTOR, "url");
    assertTrue(url.startsWith(ORIGIN + "/pay#c."), url);
    String[] parts = url.substring(url.indexOf('#') + 1).split("\\.");
    assertEquals(4, parts.length);
    assertEquals(vectorUrl.substring(vectorUrl.lastIndexOf('.') + 1), parts[3], "the relays part");
    assertEquals(Relays.encodeList(Js.strings(VECTOR, "relays")), parts[3]);
    Checkout.Link link =
        assertInstanceOf(
            Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
    assertEquals(Js.strings(VECTOR, "relays"), link.relays());

    // Its event: kind 30078, signed by its own author, the tags of §30, opening with the key of
    // the URL's link key into the vector's value exactly.
    NostrEvent event =
        merchant.net.get("wss://relay.example").received.stream()
            .filter(received -> received.pubkey().equals(link.author()))
            .findFirst()
            .orElseThrow();
    assertNotNull(Events.checked(event.toJson()));
    assertEquals(30078, (int) event.kind());
    String address = Derive.checkoutAddress(link.linkKey());
    assertEquals(
        List.of(
            List.of("d", address),
            List.of("expiration", Long.toString(invoice.expiresAt() + 604_800))),
        event.tags());
    JsonValue value = openValue(event, Derive.checkoutEncryptionKey(link.linkKey()), "checkout");
    assertEquals(Js.get(VECTOR, "value"), value);
    assertEquals(Js.write(Js.get(VECTOR, "value")), Js.write(value));
    // And the web app reads it as the vector's.
    Records.Opened<Checkout.CheckoutRecord> opened =
        Records.open(
            event.toJson(),
            Constants.CHECKOUT_RECORD_TYPE,
            Derive.checkoutEncryptionKey(link.linkKey()),
            address,
            link.author(),
            (v, e) -> Checkout.parseValue(v));
    assertNotNull(opened);
    assertEquals(21, opened.value().sats());
    assertEquals("shop.example", opened.value().redirectShown());
  }
}
