package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import me.sattle.checkout.internal.Declarations;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.Lightning;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The package's half of the cross-check with the merchant's app
 * (vectors/app/implementation-declaration.json): the vector's declaration opens here as the app
 * opens it, and the declaration this package makes end to end (createInvoice), with the app's
 * integration key and registry, opens to the same declaration, field for field. Its event is this
 * package's own (its random bytes): equal ids and signatures are not asked for.
 */
class AppVectorTest {
  private static final JsonValue APP = Js.spec("vectors/app/app.json");
  private static final JsonValue VECTOR = Js.spec("vectors/app/implementation-declaration.json");
  private static final JsonValue CHANNEL_VALUE =
      Js.get(Js.get(Js.get(APP, "channel"), "value"), "v");
  private static final Derive.Registry REGISTRY =
      new Derive.Registry(
          Js.hex(CHANNEL_VALUE, "registryKey"), Js.text(CHANNEL_VALUE, "registryReader"));
  private static final byte[] KEY = IntegrationKey.parse(Js.text(APP, "secretKey")).key();

  private TestMerchant merchant;

  @AfterEach
  void stop() {
    if (merchant != null) merchant.stop();
  }

  /** A declaration's inside, as the app opens it: the outer key, then the inner key. */
  private static JsonObject inside(NostrEvent event, Derive.Sale sale) {
    String address = event.tag("d");
    String plaintext =
        DeclarationTest.decrypt(
            event.content(), Derive.outerKey(REGISTRY.key(), address, event.pubkey()));
    // Padded with spaces to a multiple of 256 bytes (SPEC.md §12).
    assertEquals(0, Text.utf8Length(plaintext) % 256);
    JsonValue outside = Js.parse(plaintext);
    assertEquals("registry-declaration", Js.text(outside, "t"));
    return (JsonObject)
        Js.parse(DeclarationTest.decrypt(Js.text(Js.get(outside, "v"), "sealed"), sale.innerKey));
  }

  @Test
  void opensTheVectorsDeclarationAsTheAppDoes() {
    NostrEvent event = DeclarationTest.event(Js.get(VECTOR, "event"));
    Derive.Sale sale = Derive.Sale.of(KEY, REGISTRY, Js.text(VECTOR, "ref"));
    assertEquals(Js.text(VECTOR, "saleAuthor"), sale.author);
    assertEquals(Js.text(VECTOR, "taker"), sale.taker);
    assertEquals(sale.author, event.pubkey());
    assertEquals(30078, (int) event.kind());
    assertEquals(
        List.of(
            List.of("d", Derive.registryAddress(REGISTRY.key(), Js.text(VECTOR, "paymentHash")))),
        event.tags());
    assertEquals(
        Js.obj(
            "version", 1,
            "invoice", Js.text(VECTOR, "invoice"),
            "verifyUrl", Js.text(VECTOR, "verifyUrl"),
            "sale",
                Js.obj(
                    "key", Hex.encode(sale.key),
                    "fiat", Js.get(VECTOR, "fiat"),
                    "note", Js.text(VECTOR, "note")),
            "taker", sale.taker),
        inside(event, sale));
    // And as its sale reads it back (SPEC.md §21).
    Declarations.Opened opened = Declarations.openDeclaration(Js.get(VECTOR, "event"), sale);
    assertNotNull(opened);
    assertEquals(Js.text(VECTOR, "invoice"), opened.invoice());
    assertEquals(Js.text(VECTOR, "paymentHash"), opened.decoded().paymentHash());
    assertEquals(21_000L, (long) opened.decoded().amountMsat());
  }

  @Test
  void makesADeclarationThatOpensToTheVectorsEndToEnd() throws Exception {
    merchant = new TestMerchant();
    merchant.keyRelays = List.of("wss://relay.example");
    merchant.now = 1_791_380_000L;
    merchant.start();
    merchant.net.get("wss://relay.example").store(Js.get(Js.get(APP, "channel"), "event"));
    String paymentHash = Js.text(Js.get(APP, "declaration"), "paymentHash");
    // breez.tips makes an invoice of the same payment as the app's declaration of ORD-1042: the
    // app reads them as two sales claiming one payment.
    merchant.tamper =
        made -> {
          Lightning.Spec spec = new Lightning.Spec();
          spec.amountMsat = made.amountMsat();
          spec.paymentHash = paymentHash;
          spec.descriptionHash = Lightning.metadataHash(TestMerchant.METADATA);
          spec.timestamp = merchant.now;
          spec.expirySeconds = made.expiry();
          return new TestMerchant.MadeInvoice(
              Lightning.makeInvoice(spec),
              "https://breez.tips/lnurlp/verify/" + paymentHash,
              paymentHash,
              made.preimage(),
              made.amountMsat(),
              made.expiry(),
              made.comment());
        };
    SattlemeClient sattleme =
        SattlemeClient.builder()
            .key(Js.text(APP, "secretKey"))
            .httpClient(merchant.http)
            .webSocketFactory(merchant.net)
            .clock(merchant.clock())
            .random(new FixedRandom("app-vector"))
            .logger(new TestMerchant.ListLogger(merchant.warnings))
            .rateSource(RateSource.custom(currency -> RateQuote.of("99000")))
            .build();
    Invoice invoice =
        sattleme.createInvoice(
            Order.builder("ORD-2001")
                .fiat("0.02", "CHF")
                .note("Order 2001")
                .durationSeconds(600)
                .build());
    // 0.02 CHF at 99 000 CHF/BTC: ⌈2 × 10^8 / 9 900 000⌉ = ⌈20.2…⌉ = 21 sats.
    assertEquals(Js.text(VECTOR, "sats"), invoice.sats());
    assertEquals(Js.text(VECTOR, "invoice"), invoice.bolt11());
    assertEquals(Js.text(VECTOR, "paymentHash"), invoice.paymentHash());
    assertEquals(Js.text(VECTOR, "verifyUrl"), invoice.verifyUrl());
    NostrEvent made = merchant.net.get("wss://relay.example").received.get(0);
    assertNotNull(Events.checked(made.toJson()));
    Derive.Sale sale = Derive.Sale.of(KEY, REGISTRY, "ORD-2001");
    assertEquals(Js.text(VECTOR, "saleAuthor"), made.pubkey());
    assertEquals(DeclarationTest.event(Js.get(VECTOR, "event")).tags(), made.tags());
    assertEquals(inside(DeclarationTest.event(Js.get(VECTOR, "event")), sale), inside(made, sale));
    // The very same text, byte for byte, inside.
    assertEquals(
        Js.write(inside(DeclarationTest.event(Js.get(VECTOR, "event")), sale)),
        Js.write(inside(made, sale)));
    assertEquals(Bolt11.decode(Js.text(VECTOR, "invoice")), Bolt11.decode(invoice.bolt11()));
  }
}
