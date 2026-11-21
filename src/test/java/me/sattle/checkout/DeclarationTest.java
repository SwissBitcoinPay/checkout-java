package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import me.sattle.checkout.internal.Channels;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Declarations;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The app's vectors (vectors/app/app.json) and the declarations the package makes, as
 * checkout-node's declaration tests.
 */
class DeclarationTest {
  private static final JsonValue APP = Js.spec("vectors/app/app.json");
  private static final JsonValue DECLARATION = Js.get(APP, "declaration");
  private static final JsonValue REAL = Js.get(APP, "realInvoice");
  private static final byte[] INTEGRATION_KEY = Js.hex(Js.get(APP, "parsed"), "integrationKey");
  private static final JsonValue CHANNEL_VALUE =
      Js.get(Js.get(Js.get(APP, "channel"), "value"), "v");
  private static final Derive.Registry REGISTRY =
      new Derive.Registry(
          Js.hex(CHANNEL_VALUE, "registryKey"), Js.text(CHANNEL_VALUE, "registryReader"));

  static String decrypt(String payload, byte[] key) {
    try {
      return Nip44.decrypt(payload, key);
    } catch (Nip44.DecryptException | Nip44.UnknownVersionException e) {
      throw new AssertionError(e);
    }
  }

  static NostrEvent event(JsonValue value) {
    NostrEvent event = Events.checked(value);
    assertNotNull(event, "a valid event");
    return event;
  }

  static Checkout.DeclaredPrice price(JsonValue fiat) {
    return new Checkout.DeclaredPrice(
        Js.integer(fiat, "minor"), (int) Js.integer(fiat, "digits"), Js.text(fiat, "currency"));
  }

  @Nested
  class TheAppsVectors {
    @Test
    void giveTheChannelsKeysAndTheIntegrationsMarkKey() {
      assertEquals(
          Js.get(APP, "derived"),
          Js.obj(
              "channelAddress", Derive.channelAddress(INTEGRATION_KEY),
              "channelEncryptionKey", Hex.encode(Derive.channelEncryptionKey(INTEGRATION_KEY)),
              "takerKey", Hex.encode(Derive.takerKey(INTEGRATION_KEY))));
    }

    @Test
    void holdAChannelTheKeyAloneOpensAsThePackageReadsIt() {
      JsonValue channel = Js.get(APP, "channel");
      NostrEvent event = event(Js.get(channel, "event"));
      assertEquals(Js.text(Js.get(APP, "parsed"), "team"), event.pubkey());
      assertEquals(List.of(List.of("d", Derive.channelAddress(INTEGRATION_KEY))), event.tags());
      assertEquals(
          Js.get(channel, "value"),
          Js.parse(decrypt(event.content(), Derive.channelEncryptionKey(INTEGRATION_KEY))));
      assertEquals(
          new Channels.Channel(
              false,
              "Sunrise Bakery",
              "sattleme02c6047f94@breez.tips",
              Js.text(CHANNEL_VALUE, "registryKey"),
              Js.text(CHANNEL_VALUE, "registryReader"),
              List.of("wss://relay.example"),
              // The app's channel of this vector names no web app: no checkout link.
              null,
              // Nor a tolerance of on-chain underpayments: none.
              0,
              // Nor a Bitcoin node: the explorer is mempool.space.
              null,
              event.createdAt()),
          Channels.parseChannel(CHANNEL_VALUE, event.createdAt()));
    }

    @Test
    void giveEveryKeyOfTheOrdersSaleAndOfItsDeclaration() {
      Derive.Sale sale = Derive.Sale.of(INTEGRATION_KEY, REGISTRY, Js.text(DECLARATION, "ref"));
      assertEquals(Js.text(DECLARATION, "saleKey"), Hex.encode(sale.key));
      assertEquals(Js.text(DECLARATION, "saleSecret"), Hex.encode(sale.secretKey));
      assertEquals(Js.text(DECLARATION, "saleAuthor"), sale.author);
      assertEquals(Js.text(DECLARATION, "taker"), sale.taker);
      assertEquals(
          Js.text(DECLARATION, "address"),
          Derive.registryAddress(REGISTRY.key(), Js.text(DECLARATION, "paymentHash")));
      assertEquals(
          Js.text(DECLARATION, "outerKey"),
          Hex.encode(
              Derive.outerKey(REGISTRY.key(), Js.text(DECLARATION, "address"), sale.author)));
      assertEquals(
          Js.text(DECLARATION, "innerConversationKey"),
          Hex.encode(Nip44.conversationKey(sale.secretKey, REGISTRY.reader())));
      assertEquals(Js.text(DECLARATION, "innerConversationKey"), Hex.encode(sale.innerKey));
    }

    @Test
    void holdARealBreezTipsInvoiceThePackageDecodes() {
      assertEquals(
          new Bolt11(
              "bc",
              Long.parseLong(Js.text(REAL, "amountMsat")),
              Js.integer(REAL, "timestamp"),
              Js.integer(REAL, "expirySeconds"),
              Js.text(REAL, "paymentHash"),
              Js.text(REAL, "descriptionHash")),
          Bolt11.decode(Js.text(REAL, "invoice")));
    }

    @Test
    void holdADeclarationThePackageOpensAsItsSale() {
      NostrEvent event = event(Js.get(DECLARATION, "event"));
      assertEquals(
          Js.integer(DECLARATION, "outsideBytes"),
          Text.utf8Length(decrypt(event.content(), Js.hex(DECLARATION, "outerKey"))));
      Declarations.Opened opened =
          Declarations.openDeclaration(
              Js.get(DECLARATION, "event"),
              Derive.Sale.of(INTEGRATION_KEY, REGISTRY, Js.text(DECLARATION, "ref")));
      assertEquals(
          new Declarations.Opened(
              event,
              Js.text(DECLARATION, "invoice"),
              Bolt11.decode(Js.text(DECLARATION, "invoice")),
              Js.text(DECLARATION, "verifyUrl"),
              price(Js.get(DECLARATION, "fiat")),
              Js.text(DECLARATION, "note"),
              event.createdAt(),
              null),
          opened);
      // Another order's sale does not open it.
      assertNull(
          Declarations.openDeclaration(
              Js.get(DECLARATION, "event"), Derive.Sale.of(INTEGRATION_KEY, REGISTRY, "ORD-1043")));
    }
  }

  @Nested
  class ADeclarationThePackageMakes {
    private final NostrEvent event =
        Declarations.declarationEvent(
            Derive.Sale.of(INTEGRATION_KEY, REGISTRY, Js.text(DECLARATION, "ref")),
            Js.text(DECLARATION, "invoice"),
            Js.text(DECLARATION, "verifyUrl"),
            Js.text(DECLARATION, "paymentHash"),
            price(Js.get(DECLARATION, "fiat")),
            Js.text(DECLARATION, "note"),
            Js.integer(Js.get(DECLARATION, "event"), "created_at"),
            null,
            new RandomBytes(new java.security.SecureRandom()));

    private JsonValue inside(String content) {
      JsonObject outside = (JsonObject) Js.parse(decrypt(content, Js.hex(DECLARATION, "outerKey")));
      assertEquals("registry-declaration", Js.text(outside, "t"));
      return Js.parse(
          decrypt(
              Js.text(Js.get(outside, "v"), "sealed"),
              Js.hex(DECLARATION, "innerConversationKey")));
    }

    @Test
    void opensWithTheVectorsKeysHoldingWhatTheAppsHolds() {
      assertNotNull(Events.checked(event.toJson()));
      assertEquals(30078, (int) event.kind());
      assertEquals(Js.text(DECLARATION, "saleAuthor"), event.pubkey());
      assertEquals(List.of(List.of("d", Js.text(DECLARATION, "address"))), event.tags());
      String outside = decrypt(event.content(), Js.hex(DECLARATION, "outerKey"));
      assertEquals(0, Text.utf8Length(outside) % 256);
      JsonValue inside = inside(event.content());
      JsonValue app = inside(Js.text(Js.get(DECLARATION, "event"), "content"));
      // The same declaration, field for field, as the vector's (which the app made).
      assertEquals(app, inside);
      assertEquals(
          Js.obj(
              "version", 1,
              "invoice", Js.text(DECLARATION, "invoice"),
              "verifyUrl", Js.text(DECLARATION, "verifyUrl"),
              "sale",
                  Js.obj(
                      "key", Js.text(DECLARATION, "saleKey"),
                      "fiat", Js.get(DECLARATION, "fiat"),
                      "note", Js.text(DECLARATION, "note")),
              "taker", Js.text(DECLARATION, "taker")),
          inside);
    }

    @Test
    void isRefusedWithAnotherRegistryKeyOrReader() {
      Derive.Registry otherKey =
          new Derive.Registry(me.sattle.checkout.support.FixedRandom.bytes(9), REGISTRY.reader());
      assertNull(
          Declarations.openDeclaration(
              event.toJson(),
              Derive.Sale.of(INTEGRATION_KEY, otherKey, Js.text(DECLARATION, "ref"))));
      Derive.Registry otherReader =
          new Derive.Registry(REGISTRY.key(), Js.text(Js.get(APP, "parsed"), "team"));
      assertNull(
          Declarations.openDeclaration(
              event.toJson(),
              Derive.Sale.of(INTEGRATION_KEY, otherReader, Js.text(DECLARATION, "ref"))));
    }
  }
}
