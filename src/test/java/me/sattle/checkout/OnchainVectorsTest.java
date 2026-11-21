package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Declarations;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.Http;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.Lnurl;
import me.sattle.checkout.internal.Model;
import me.sattle.checkout.internal.Orders;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Sales;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonBoolean;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.lightning.Bolt11;
import me.sattle.checkout.internal.nostr.Entry;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Acceptances;
import me.sattle.checkout.internal.onchain.Addresses;
import me.sattle.checkout.internal.onchain.BitcoinNode;
import me.sattle.checkout.internal.onchain.CallExplorer;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.internal.onchain.Lot;
import me.sattle.checkout.internal.onchain.MerchantAcceptances;
import me.sattle.checkout.internal.onchain.Methods;
import me.sattle.checkout.internal.onchain.NodeUrl;
import me.sattle.checkout.internal.onchain.Payments;
import me.sattle.checkout.internal.onchain.Replacements;
import me.sattle.checkout.internal.onchain.Rests;
import me.sattle.checkout.internal.onchain.Tolerance;
import me.sattle.checkout.internal.onchain.Witnesses;
import me.sattle.checkout.support.FakeHttp;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.RelayNet;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The package against the on-chain conformance vectors (SPEC.md §31) of the pinned copy of the
 * specification: deposit addresses, the methods of an order, leases, witnesses, replacements,
 * complete reads, tolerances, earlier invoices and the statuses of an invoice with an address, as
 * checkout-node's on-chain vector tests. Every event the vectors give is opened.
 */
class OnchainVectorsTest {
  private static final JsonValue APP = Js.spec("vectors/app/app.json");
  private static final byte[] APP_KEY = Js.hex(Js.get(APP, "parsed"), "integrationKey");
  private static final JsonValue APP_DECLARATION = Js.get(APP, "declaration");
  private static final RandomBytes RANDOM = new RandomBytes(new SecureRandom());
  private static final Warn QUIET = message -> {};

  private static JsonValue vectors(String name) {
    return Js.spec("vectors/" + name);
  }

  private static String quoted(String text) {
    return Js.write(new JsonString(text));
  }

  private static boolean has(JsonValue object, String name) {
    return ((JsonObject) object).has(name);
  }

  private static String decrypt(String payload, byte[] key) {
    return DeclarationTest.decrypt(payload, key);
  }

  // vectors/app/onchain-lot.json

  @Test
  void opensTheLotTheMerchantsAppMadeWithTheIntegrationKeyAlone() {
    JsonValue vector = vectors("app/onchain-lot.json");
    IntegrationKey access = IntegrationKey.parse(Js.text(vector, "secretKey"));
    byte[] encryptionKey = Derive.channelEncryptionKey(access.key());
    assertEquals(
        Js.obj(
            "team", Js.text(vector, "team"),
            "lotAddress", Js.text(vector, "lotAddress"),
            "channelEncryptionKey", Js.text(vector, "channelEncryptionKey")),
        Js.obj(
            "team", access.teamPublicKey(),
            "lotAddress", Derive.lotAddress(access.key()),
            "channelEncryptionKey", Hex.encode(encryptionKey)));
    JsonValue lot = Js.get(vector, "lot");
    Records.Opened<List<String>> opened =
        Records.open(
            Js.get(lot, "event"),
            Js.text(Js.get(lot, "value"), "t"),
            encryptionKey,
            Js.text(vector, "lotAddress"),
            Js.text(vector, "team"),
            (value, event) -> Lot.parseLot(value, event.createdAt()));
    assertNotNull(opened);
    assertEquals(Js.strings(Js.get(Js.get(lot, "value"), "v"), "addresses"), opened.value());
  }

  // vectors/app/merchant-acceptance.json

  private static final JsonValue MERCHANT_ACCEPTANCE = vectors("app/merchant-acceptance.json");
  private static final JsonValue MADE = Js.get(MERCHANT_ACCEPTANCE, "made");

  @Test
  void opensTheMerchantsAcceptancesWithTheTeamAndTheChannelsKeyOfTheIntegrationKey() {
    IntegrationKey access = IntegrationKey.parse(Js.text(MERCHANT_ACCEPTANCE, "secretKey"));
    assertEquals(
        Js.obj(
            "team", Js.text(MERCHANT_ACCEPTANCE, "team"),
            "channelEncryptionKey", Js.text(MERCHANT_ACCEPTANCE, "channelEncryptionKey")),
        Js.obj(
            "team", access.teamPublicKey(),
            "channelEncryptionKey", Hex.encode(Derive.channelEncryptionKey(access.key()))));
    // made: by a team of its own, for the same integration.
    IntegrationKey made = IntegrationKey.parse(Js.text(MADE, "secretKey"));
    assertEquals(Js.text(MADE, "team"), made.teamPublicKey());
    assertEquals(Js.text(MADE, "team"), Curve.publicKeyOf(Js.hex(MADE, "teamSecret")));
    assertArrayEquals(access.key(), made.key());
  }

  @TestFactory
  Stream<DynamicTest> merchantAcceptances() {
    IntegrationKey access = IntegrationKey.parse(Js.text(MERCHANT_ACCEPTANCE, "secretKey"));
    IntegrationKey made = IntegrationKey.parse(Js.text(MADE, "secretKey"));
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue invoice : Js.list(MERCHANT_ACCEPTANCE, "invoices")) {
      tests.add(
          dynamicTest(
              "the merchant's acceptance address of "
                  + Js.text(invoice, "ref")
                  + " at "
                  + Js.integer(invoice, "expiresAt"),
              () -> {
                byte[] saleKey = Derive.Sale.saleKey(access.key(), Js.text(invoice, "ref"));
                assertEquals(Js.text(invoice, "saleKey"), Hex.encode(saleKey));
                assertEquals(
                    Js.text(invoice, "sale"), Curve.publicKeyOf(Derive.Sale.saleSecret(saleKey)));
                assertEquals(
                    Js.text(invoice, "acceptanceAddress"),
                    Derive.merchantAcceptanceAddress(
                        access.key(),
                        Js.text(invoice, "sale"),
                        Js.text(invoice, "onchainAddress"),
                        Js.integer(invoice, "expiresAt")));
              }));
    }
    for (JsonValue c : Js.list(MERCHANT_ACCEPTANCE, "records")) {
      tests.add(dynamicTest(Js.text(c, "name"), () -> readMerchantAcceptance(c, access)));
    }
    for (JsonValue c : Js.list(MADE, "records")) {
      tests.add(dynamicTest(Js.text(c, "name"), () -> readMerchantAcceptance(c, made)));
    }
    return tests.stream();
  }

  /**
   * {@code c} read as a check reads it for its invoice, with the integration key {@code access}:
   * through the reading of one record (MerchantAcceptances.openMerchantAcceptance), and through the
   * count of a read on every relay where each relay of 3 sent it
   * (MerchantAcceptances.merchantAcceptanceOf).
   */
  private static void readMerchantAcceptance(JsonValue c, IntegrationKey access) {
    JsonValue invoice =
        Js.list(MERCHANT_ACCEPTANCE, "invoices").get((int) Js.integer(c, "invoice"));
    MerchantAcceptances.Keys keys =
        MerchantAcceptances.Keys.of(
            access.key(),
            access.teamPublicKey(),
            Js.text(invoice, "sale"),
            Js.text(invoice, "onchainAddress"),
            Js.integer(invoice, "expiresAt"));
    long sats = Long.parseLong(Js.text(invoice, "sats"));
    long expiresAt = Js.integer(invoice, "expiresAt");
    JsonValue event = Js.get(c, "event");
    MerchantAcceptances.Opened opened =
        MerchantAcceptances.openMerchantAcceptance(event, keys, sats, expiresAt);
    JsonValue read =
        opened == null || opened.status() == MerchantAcceptances.Status.NEWER_SET_ASIDE
            ? JsonNull.NULL
            : opened.status() == MerchantAcceptances.Status.NEWER
                ? new JsonString("newer")
                : Js.obj("received", opened.received(), "createdAt", opened.event().createdAt());
    JsonValue expected = Js.get(c, "expected");
    assertEquals(Js.write(expected), Js.write(read));
    Map<String, List<JsonValue>> everyRelay = new LinkedHashMap<>();
    for (String relay : List.of("a", "b", "c")) everyRelay.put(relay, List.of(event));
    assertEquals(
        expected instanceof JsonObject ? Js.integer(expected, "received") : null,
        MerchantAcceptances.merchantAcceptanceOf(everyRelay, keys, sats, expiresAt, 3).received());
  }

  // onchain-addresses.json

  @TestFactory
  Stream<DynamicTest> onchainAddresses() {
    JsonValue vector = vectors("onchain-addresses.json");
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(vector, "addresses")) {
      tests.add(
          dynamicTest(
              Js.text(c, "name") + ": " + Js.bool(c, "valid"),
              () ->
                  assertEquals(
                      Js.bool(c, "valid"), Addresses.isDepositAddress(Js.text(c, "address")))));
    }
    JsonValue registry = Js.get(vector, "registry");
    for (JsonValue c : Js.list(registry, "cases")) {
      tests.add(
          dynamicTest(
              "the registry address of " + Js.text(c, "address"),
              () ->
                  assertEquals(
                      Js.text(c, "registryAddress"),
                      Leases.leaseAddress(
                          Js.hex(registry, "registryKey"), Js.text(c, "address")))));
    }
    for (JsonValue c : Js.list(vector, "uris")) {
      tests.add(
          dynamicTest(
              "payment URI: " + Js.text(c, "name"),
              () ->
                  assertEquals(
                      Js.text(c, "uri"),
                      Addresses.paymentUri(
                          Js.text(c, "address"),
                          new BigInteger(Js.text(c, "sats")),
                          Js.text(c, "bolt11")))));
    }
    for (JsonValue c : Js.list(vector, "amounts")) {
      tests.add(
          dynamicTest(
              Js.text(c, "sats") + " sats is " + Js.text(c, "btc") + " BTC",
              () ->
                  assertEquals(
                      Js.text(c, "btc"), Addresses.btcAmount(Long.parseLong(Js.text(c, "sats"))))));
    }
    return tests.stream();
  }

  // payment-methods.json

  @TestFactory
  Stream<DynamicTest> paymentMethods() {
    return Js.list(vectors("payment-methods.json"), "cases").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  String address = Js.text(c, "address");
                  Methods.LeaseOutcome leased =
                      switch (address) {
                        case "leased" -> new Methods.Leased("bc1p", null);
                        case "none" -> new Methods.NoAddress();
                        case "explorer_failed" ->
                            new Methods.ExplorerFailed(
                                new NetworkException("The explorer did not answer"));
                        default -> throw new IllegalArgumentException(address);
                      };
                  JsonValue sendable = Js.get(c, "sendable");
                  assertEquals(
                      Js.get(c, "expected"),
                      Js.outcome(() -> {
                        Orders.CheckedOrder order =
                            Orders.checkOrder(
                                Order.builder("A-1").sats(Js.text(c, "sats")).build(), List.of());
                        Methods.Of methods =
                            Methods.methodsWith(
                                Methods.paymentMethods(
                                    order.sats(),
                                    Long.parseLong(Js.text(sendable, "min")),
                                    Long.parseLong(Js.text(sendable, "max")),
                                    "sats"),
                                leased);
                        List<String> names = new ArrayList<>();
                        if (methods.lightning()) names.add("lightning");
                        if (methods.onchain()) names.add("onchain");
                        return Js.obj("methods", names);
                      }));
                }));
  }

  // onchain-leases.json

  private static final JsonValue LEASES = vectors("onchain-leases.json");
  private static final byte[] REGISTRY_KEY = Js.hex(LEASES, "registryKey");

  /** The sale of vectors/app/app.json's declaration, in this registry. */
  private static final Derive.Sale SALE =
      Derive.Sale.of(
          APP_KEY,
          new Derive.Registry(REGISTRY_KEY, Js.text(LEASES, "registryReader")),
          Js.text(APP_DECLARATION, "ref"));

  @Test
  void isTheSaleOfTheAppsDeclaration() {
    assertEquals(
        Js.obj(
            "saleAuthor", Js.text(LEASES, "saleAuthor"),
            "saleSecret", Js.text(LEASES, "saleSecret"),
            "innerConversationKey", Js.text(LEASES, "innerConversationKey")),
        Js.obj(
            "saleAuthor", SALE.author,
            "saleSecret", Hex.encode(SALE.secretKey),
            "innerConversationKey", Hex.encode(SALE.innerKey)));
  }

  @TestFactory
  Stream<DynamicTest> theKeysOfEachAddress() {
    return Js.list(LEASES, "derived").stream()
        .map(c ->
            dynamicTest(
                "the keys of " + Js.text(c, "address"),
                () -> {
                  String d = Leases.leaseAddress(REGISTRY_KEY, Js.text(c, "address"));
                  assertEquals(
                      Js.obj(
                          "registryAddress", Js.text(c, "registryAddress"),
                          "outerKey", Js.text(c, "outerKey")),
                      Js.obj(
                          "registryAddress",
                          d,
                          "outerKey",
                          Hex.encode(Derive.outerKey(REGISTRY_KEY, d, SALE.author))));
                }));
  }

  private static JsonValue outsideOf(Leases.OpenedOutside opened) {
    if (opened == null) return JsonNull.NULL;
    Leases.Outside outside = opened.outside();
    return switch (outside.kind()) {
      case NEWER -> new JsonString("newer");
      case WITHDRAWN -> Js.obj("version", 1, "withdrawn", true);
      case LEASE -> Js.obj("version", 1, "end", outside.end(), "sealed", outside.sealed());
    };
  }

  @TestFactory
  Stream<DynamicTest> leaseEvents() {
    return Js.list(LEASES, "events").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  JsonValue event = Js.get(c, "event");
                  JsonValue inside = Js.get(c, "inside");
                  boolean signature = !has(c, "signature") || Js.bool(c, "signature");
                  String d = Leases.leaseAddress(REGISTRY_KEY, Js.text(c, "address"));
                  Leases.LeaseRecord record = Leases.openLeaseRecord(event, REGISTRY_KEY, d);
                  Leases.OpenedOutside whole = Leases.openLeaseOutside(event, REGISTRY_KEY, d);
                  assertEquals(Js.get(c, "outside"), outsideOf(whole));
                  // A read keeps of it only its id, author, date and signature, and its
                  // outside's end.
                  if (whole == null) {
                    assertNull(record);
                  } else {
                    assertEquals(
                        new Leases.LeaseEvent(
                            Js.text(event, "id"),
                            Js.text(event, "pubkey"),
                            Js.integer(event, "created_at"),
                            Js.text(event, "sig")),
                        record.event());
                  }
                  // Opening it checks no signature; the record checks it when asked.
                  assertEquals(
                      signature,
                      Events.signatureChecks(
                          Js.text(event, "id"), Js.text(event, "pubkey"), Js.text(event, "sig")));
                  if (record != null) assertEquals(signature, record.signed());
                  Leases.OpenedLease opened =
                      whole != null && whole.outside().kind() == Leases.Kind.LEASE
                          ? Leases.openLeaseInside(whole.outside(), d, SALE)
                          : null;
                  assertEquals(inside, opened == null ? JsonNull.NULL : opened.inside());
                  // The order's tolerance it gives: absent, or not one, none.
                  Integer tolerance =
                      inside == JsonNull.NULL
                          ? null
                          : has(c, "tolerance") ? (int) Js.integer(c, "tolerance") : 0;
                  assertEquals(tolerance, opened == null ? null : opened.terms().tolerance());
                  // Confirmed: `confirmed` true alone (the vector's field, absent: true).
                  Boolean confirmed =
                      inside == JsonNull.NULL
                          ? null
                          : !has(c, "confirmed") || Js.bool(c, "confirmed");
                  assertEquals(
                      confirmed, opened == null ? null : Leases.isConfirmedLease(opened.terms()));
                }));
  }

  /**
   * A lease the package makes for the same sale and terms opens with the vector's keys into the
   * vector's inside, the very same text. A tolerance in a fraction is left out: a lease's tolerance
   * is the channel's, read as an integer from 0 to 1000 (SPEC.md §31), which the package writes.
   */
  @TestFactory
  Stream<DynamicTest> makesEachLeaseAgain() {
    return Js.list(LEASES, "events").stream()
        .filter(c -> Js.get(c, "inside") != JsonNull.NULL)
        .filter(c -> {
          JsonValue tolerance = Js.get(Js.get(c, "inside"), "tolerance");
          return tolerance == null || Json.isInteger(tolerance);
        })
        .map(c ->
            dynamicTest(
                "makes " + Js.text(c, "name") + " again",
                () -> {
                  JsonValue v = Js.get(c, "inside");
                  JsonValue sale = Js.get(v, "sale");
                  JsonValue fiat = Js.get(sale, "fiat");
                  String address = Js.text(c, "address");
                  Leases.Terms terms =
                      new Leases.Terms(
                          address,
                          Js.integer(v, "sats"),
                          Js.text(v, "invoice"),
                          Js.text(v, "verifyUrl"),
                          Js.integer(v, "createdAt"),
                          Js.integer(v, "expiresAt"),
                          new Checkout.DeclaredPrice(
                              Js.integer(fiat, "minor"),
                              (int) Js.integer(fiat, "digits"),
                              Js.text(fiat, "currency")),
                          Js.text(sale, "note"),
                          has(v, "tolerance") ? (int) Js.integer(v, "tolerance") : 0,
                          has(v, "confirmed") ? Js.bool(v, "confirmed") : null);
                  NostrEvent made =
                      Leases.leaseEvent(
                          SALE, terms, Js.integer(Js.get(c, "event"), "created_at"), RANDOM);
                  String d = Leases.leaseAddress(REGISTRY_KEY, address);
                  assertEquals(List.of(List.of("d", d)), made.tags());
                  assertEquals(Js.text(LEASES, "saleAuthor"), made.pubkey());
                  JsonValue derived =
                      Js.list(LEASES, "derived").stream()
                          .filter(item -> address.equals(Js.text(item, "address")))
                          .findFirst()
                          .orElseThrow();
                  String plaintext = decrypt(made.content(), Js.hex(derived, "outerKey"));
                  assertEquals(0, Text.utf8Length(plaintext) % 256);
                  JsonValue record = Js.parse(plaintext);
                  assertEquals("registry-onchain", Js.text(record, "t"));
                  assertEquals(
                      Js.integer(Js.get(c, "outside"), "end"),
                      Js.integer(Js.get(record, "v"), "end"));
                  String sealed =
                      decrypt(
                          Js.text(Js.get(record, "v"), "sealed"),
                          Js.hex(LEASES, "innerConversationKey"));
                  assertEquals(Js.write(v), sealed);
                  assertArrayEquals(
                      Js.hex(LEASES, "innerConversationKey"),
                      Nip44.conversationKey(SALE.secretKey, Js.text(LEASES, "registryReader")));
                }));
  }

  /** The records of a vector, as opened (one whose id is not its hash never is). */
  private static List<Leases.LeaseRecord> recordsOf(
      List<JsonValue> records, List<Integer> checked) {
    List<Leases.LeaseRecord> out = new ArrayList<>();
    for (int index = 0; index < records.size(); index += 1) {
      JsonValue r = records.get(index);
      if (Js.get(r, "id") == JsonBoolean.FALSE) continue;
      JsonValue outside = Js.get(r, "outside");
      Leases.Hold hold =
          outside instanceof JsonString
              ? new Leases.Hold(Leases.Kind.NEWER, 0)
              : Js.bool(outside, "withdrawn")
                  ? new Leases.Hold(Leases.Kind.WITHDRAWN, 0)
                  : new Leases.Hold(Leases.Kind.LEASE, Js.integer(outside, "end"));
      String id =
          has(r, "eventId")
              ? Js.text(r, "eventId")
              : "0".repeat(64 - String.valueOf(index).length()) + index;
      boolean signed = !has(r, "signature") || Js.bool(r, "signature");
      int position = index;
      out.add(
          new Leases.LeaseRecord(
              new Leases.LeaseEvent(id, Js.text(r, "author"), Js.integer(r, "createdAt"), ""),
              "",
              hold,
              () -> {
                checked.add(position);
                return signed;
              },
              sig -> {}));
    }
    return out;
  }

  @TestFactory
  Stream<DynamicTest> leased() {
    return Js.list(LEASES, "leased").stream()
        .map(c ->
            dynamicTest(
                "leased: " + Js.text(c, "name"),
                () -> {
                  List<Integer> asked = new ArrayList<>();
                  assertEquals(
                      Js.bool(c, "expected"),
                      Leases.isLeased(recordsOf(Js.list(c, "records"), asked), Js.integer(c, "t")));
                  // The signatures checked: those of the records that would end an older
                  // one's hold.
                  asked.sort(null);
                  assertEquals(Js.get(c, "checked"), Js.of(asked));
                }));
  }

  @TestFactory
  Stream<DynamicTest> confirmed() {
    return Js.list(LEASES, "confirmed").stream()
        .map(c ->
            dynamicTest(
                "confirmed: " + Js.text(c, "name"),
                () -> {
                  List<Leases.LeaseRecord> read =
                      recordsOf(Js.list(c, "records"), new ArrayList<>());
                  // This lease, or one that is not among the records read.
                  Leases.LeaseEvent own;
                  if (Js.get(c, "lease") == JsonNull.NULL) {
                    own =
                        new Leases.LeaseEvent(
                            "f".repeat(64), Js.text(c, "sale"), Js.integer(c, "t"), "");
                  } else {
                    long lease = Js.integer(c, "lease");
                    String id = "0".repeat(64 - Long.toString(lease).length()) + lease;
                    own =
                        read.stream()
                            .map(Leases.LeaseRecord::event)
                            .filter(event -> event.id().equals(id))
                            .findFirst()
                            .orElseThrow();
                  }
                  assertEquals(
                      Js.bool(c, "expected"),
                      Js.bool(c, "complete") && Leases.isConfirmed(read, own, Js.integer(c, "t")));
                }));
  }

  // onchain-witnesses.json

  private static final JsonValue WITNESSES = vectors("onchain-witnesses.json");
  private static final Derive.SeenKeys SEEN =
      Derive.SeenKeys.of(Js.hex(WITNESSES, "integrationKey"));

  @Test
  void derivesTheSeenKeys() {
    assertEquals(
        Js.get(WITNESSES, "derived"),
        Js.obj(
            "seenSecret", Hex.encode(SEEN.secretKey()),
            "seenAuthor", SEEN.author(),
            "seenAddressKey", Hex.encode(SEEN.addressKey())));
    assertEquals(Js.text(WITNESSES, "channelEncryptionKey"), Hex.encode(SEEN.encryptionKey()));
  }

  @TestFactory
  Stream<DynamicTest> witnessTagsAndAddresses() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(WITNESSES, "addresses")) {
      tests.add(
          dynamicTest(
              "the witness address of " + Js.text(c, "txid") + ", " + Js.text(c, "sale"),
              () ->
                  assertEquals(
                      Js.text(c, "witnessAddress"),
                      Witnesses.witnessAddress(
                          SEEN.addressKey(),
                          Js.text(c, "address"),
                          Js.text(c, "txid"),
                          Js.text(c, "sale")))));
    }
    for (JsonValue c : Js.list(WITNESSES, "orderTags")) {
      tests.add(
          dynamicTest(
              "the order tag of " + Js.text(c, "address") + ", " + Js.text(c, "sale"),
              () ->
                  assertEquals(
                      Js.text(c, "orderTag"),
                      Witnesses.orderTag(
                          SEEN.addressKey(), Js.text(c, "address"), Js.text(c, "sale")))));
    }
    for (JsonValue c : Js.list(WITNESSES, "addressTags")) {
      tests.add(
          dynamicTest(
              "the address tag of " + Js.text(c, "address"),
              () ->
                  assertEquals(
                      Js.text(c, "addressTag"),
                      Witnesses.addressTag(SEEN.addressKey(), Js.text(c, "address")))));
    }
    return tests.stream();
  }

  private static JsonValue valueOf(JsonValue event) {
    Witnesses.Opened opened = Witnesses.openWitness(event, SEEN);
    if (opened == null) return JsonNull.NULL;
    if (opened.isNewer()) return new JsonString("newer");
    Witnesses.Witness witness = opened.witness();
    return Js.obj(
        "version",
        1,
        "address",
        witness.address(),
        "txid",
        witness.txid(),
        "sale",
        witness.sale(),
        "sats",
        witness.sats(),
        "inputs",
        witness.inputs(),
        "replacement",
        witness.replacement(),
        "expiresAt",
        witness.expiresAt());
  }

  @TestFactory
  Stream<DynamicTest> witnessEvents() {
    return Js.list(WITNESSES, "events").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> assertEquals(Js.get(c, "value"), valueOf(Js.get(c, "event")))));
  }

  /**
   * The addresses an event names when leasing (SPEC.md §31, "Leasing an address", step 3), as
   * readNamedAddresses keeps them: any event of the seen author that checks out names each address
   * whose address tag it holds, whatever its content.
   */
  @TestFactory
  Stream<DynamicTest> witnessNames() {
    Map<String, String> byTag = new LinkedHashMap<>();
    for (JsonValue c : Js.list(WITNESSES, "addressTags")) {
      byTag.put(
          Witnesses.addressTag(SEEN.addressKey(), Js.text(c, "address")), Js.text(c, "address"));
    }
    return Js.list(WITNESSES, "events").stream()
        .map(c ->
            dynamicTest(
                "names " + Js.write(Js.get(c, "names")) + ": " + Js.text(c, "name"),
                () -> {
                  List<String> named =
                      new ArrayList<>(
                          Witnesses.addressesNamed(Js.get(c, "event"), SEEN.author(), byTag));
                  List<String> expected = new ArrayList<>(Js.strings(c, "names"));
                  Collections.sort(named);
                  Collections.sort(expected);
                  assertEquals(expected, named);
                }));
  }

  /**
   * A witness the package makes for the same value has the vector's {@code d} and {@code o} tags (a
   * vector's other tags are ignored), and opens to it.
   */
  @TestFactory
  Stream<DynamicTest> makesEachWitnessAgain() {
    return Js.list(WITNESSES, "events").stream()
        .filter(c -> Js.get(c, "value") instanceof JsonObject)
        .map(c ->
            dynamicTest(
                "makes " + Js.text(c, "name") + " again",
                () -> {
                  JsonValue v = Js.get(c, "value");
                  JsonValue event = Js.get(c, "event");
                  NostrEvent made =
                      Witnesses.witnessEvent(
                          SEEN,
                          new Witnesses.Witness(
                              Js.text(v, "address"),
                              Js.text(v, "txid"),
                              Js.text(v, "sale"),
                              Js.integer(v, "sats"),
                              Js.strings(v, "inputs"),
                              Js.bool(v, "replacement"),
                              Js.get(v, "expiresAt") == JsonNull.NULL
                                  ? null
                                  : Js.integer(v, "expiresAt")),
                          Js.integer(event, "created_at"),
                          RANDOM);
                  assertEquals(Js.text(Js.get(WITNESSES, "derived"), "seenAuthor"), made.pubkey());
                  List<List<String>> tags = new ArrayList<>();
                  for (JsonValue tag : Js.list(event, "tags")) {
                    List<String> strings = new ArrayList<>();
                    for (JsonValue item : Js.items(tag)) strings.add(((JsonString) item).value());
                    if (strings.get(0).equals("d") || strings.get(0).equals("o")) tags.add(strings);
                  }
                  assertEquals(tags, made.tags());
                  assertEquals(v, valueOf(made.toJson()));
                }));
  }

  // onchain-replacements.json

  @TestFactory
  Stream<DynamicTest> replacements() {
    return Js.list(vectors("onchain-replacements.json"), "cases").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  List<Replacements.PaymentTx> txs = new ArrayList<>();
                  for (JsonValue tx : Js.list(c, "txs")) {
                    txs.add(
                        new Replacements.PaymentTx(
                            Js.text(tx, "txid"),
                            Long.parseLong(Js.text(tx, "sats")),
                            Js.strings(tx, "inputs"),
                            Js.get(tx, "order") == JsonNull.NULL ? null : Js.integer(tx, "order"),
                            Js.bool(tx, "listed")));
                  }
                  JsonObject parts = new JsonObject();
                  for (Map.Entry<String, List<Replacements.Part>> entry :
                      Replacements.paymentParts(txs).entrySet()) {
                    List<Object> list = new ArrayList<>();
                    for (Replacements.Part part : entry.getValue()) {
                      list.add(Js.obj("sats", Long.toString(part.sats()), "root", part.root()));
                    }
                    parts.put(entry.getKey(), Js.of(list));
                  }
                  assertEquals(Js.get(c, "parts"), parts);
                }));
  }

  // onchain-payments.json

  private static final JsonValue PAYMENTS = vectors("onchain-payments.json");
  private static final JsonValue PAID_INVOICE = Js.get(PAYMENTS, "invoice");
  private static final String ADDRESS = Js.text(PAID_INVOICE, "onchainAddress");
  private static final long SATS = Long.parseLong(Js.text(PAID_INVOICE, "sats"));
  private static final long EXPIRES_AT = Js.integer(PAID_INVOICE, "expiresAt");
  private static final String SALE_AUTHOR = Js.text(APP_DECLARATION, "saleAuthor");
  private static final Derive.SeenKeys APP_SEEN = Derive.SeenKeys.of(APP_KEY);

  private static String witnessAt(String txid, String of) {
    return Witnesses.witnessAddress(APP_SEEN.addressKey(), ADDRESS, txid, of);
  }

  private static RuntimeException errorOf(String code) {
    return switch (code) {
      case "network" -> new NetworkException("The Lightning address did not answer");
      case "invalid_invoice" -> new InvalidInvoiceException("Not this invoice");
      default -> throw new IllegalArgumentException("No error " + code + " in these vectors");
    };
  }

  private static Lnurl.LightningPart lightningOf(JsonValue result) {
    String state = Js.text(result, "state");
    return switch (state) {
      case "open" -> Lnurl.LightningPart.OPEN;
      case "expired" -> Lnurl.LightningPart.EXPIRED;
      case "paid" -> Lnurl.LightningPart.paid(Js.text(result, "preimage"));
      default -> throw new IllegalArgumentException(state);
    };
  }

  private static Tasks.Settled<Lnurl.LightningPart> settledLightning(JsonValue result) {
    return has(result, "error")
        ? Tasks.Settled.failed(errorOf(Js.text(result, "error")))
        : Tasks.Settled.ok(lightningOf(result));
  }

  private static FakeHttp.Answer text(JsonValue value) {
    return FakeHttp.Answer.text(200, value instanceof JsonString s ? s.value() : Js.write(value));
  }

  /** Another order's deposit address, and another sale (of the app's key). */
  private static final String OTHER_ADDRESS =
      "bc1p4gmh64rd4m5y5nn8hwfnvtz2mm6lngpmwys5a257klyg03ju0pyqu65xe8";

  private static final String OTHER_SALE =
      Curve.publicKeyOf(Derive.Sale.saleSecret(Derive.Sale.saleKey(APP_KEY, "ANOTHER-ORDER")));

  @Test
  void makesWitnessesOfAnotherOrderAtADepositAddressOfItsOwn() {
    assertTrue(Addresses.isDepositAddress(OTHER_ADDRESS));
    assertNotEquals(ADDRESS, OTHER_ADDRESS);
    assertNotEquals(SALE_AUTHOR, OTHER_SALE);
  }

  /**
   * The event of a witness of the vector, as a relay sends it: made by the seen secret as the
   * client makes witnesses (Witnesses.witnessEvent), at the invoice's address or, {@code address}
   * "other", at another order's; for the order's sale ("this", with the {@code expiresAt} of the
   * invoice it is of), another sale (with the invoice's {@code expiresAt}: nothing here depends on
   * it) or none. One of a newer format: a record of the seen secret at the witness address of its
   * txid and the order's sale ("newer") or none ("newer none"), with that address's {@code o} tags,
   * dated {@code newerAt} (the vector gives no date of it). As checkout-node's runner.
   */
  private static JsonValue eventOf(JsonValue w, long newerAt) {
    String at = "other".equals(Js.text(w, "address")) ? OTHER_ADDRESS : ADDRESS;
    String txid = Js.text(w, "txid");
    JsonValue sale = Js.get(w, "sale");
    String named = sale instanceof JsonString text ? text.value() : null;
    if ("newer".equals(named) || "newer none".equals(named)) {
      String of = "newer".equals(named) ? SALE_AUTHOR : null;
      return Records.seal(
              Constants.WITNESS_RECORD_TYPE,
              Js.obj("version", Constants.WITNESS_VERSION + 1),
              APP_SEEN.secretKey(),
              APP_SEEN.encryptionKey(),
              Witnesses.witnessAddress(APP_SEEN.addressKey(), at, txid, of),
              newerAt,
              List.of(
                  List.of(Constants.ORDER_TAG, Witnesses.orderTag(APP_SEEN.addressKey(), at, of)),
                  List.of(Constants.ORDER_TAG, Witnesses.addressTag(APP_SEEN.addressKey(), at))),
              RANDOM)
          .toJson();
    }
    String of = "this".equals(named) ? SALE_AUTHOR : "other".equals(named) ? OTHER_SALE : null;
    return Witnesses.witnessEvent(
            APP_SEEN,
            new Witnesses.Witness(
                at,
                txid,
                of,
                Js.integer(w, "sats"),
                Js.strings(w, "inputs"),
                Js.bool(w, "replacement"),
                of == null ? null : has(w, "expiresAt") ? Js.integer(w, "expiresAt") : EXPIRES_AT),
            Js.integer(w, "seenAt"),
            RANDOM)
        .toJson();
  }

  /**
   * The witnesses a read gives of what the relays hold ({@code held}: the vector's witnesses, or
   * "unreadable"), and of {@code published}: what the relays send it, read with
   * Witnesses.orderWitnessesOf, as Witnesses.readOrderWitnesses reads what they send.
   */
  private static Witnesses.OrderWitnesses witnessesOf(
      JsonValue held, long now, List<JsonValue> published) {
    if (held instanceof JsonString) {
      throw new NetworkException("Fewer than a majority of the merchant's relays answered");
    }
    List<JsonValue> sent = new ArrayList<>();
    for (JsonValue w : Js.items(held)) sent.add(eventOf(w, now - 1));
    sent.addAll(published);
    return Witnesses.orderWitnessesOf(sent, APP_SEEN, ADDRESS, SALE_AUTHOR, EXPIRES_AT);
  }

  /** The merchant's relays of the acceptance records, local. */
  private static final List<String> ACCEPTANCE_RELAYS =
      List.of("wss://acceptance-1.test", "wss://acceptance-2.test", "wss://acceptance-3.test");

  /**
   * What the check publishes of an acceptance record when its status is {@code status}, as the
   * client does it (Acceptances.publishAcceptance), on local relays that hold one of {@code
   * accepted} (null: none holds one): the {@code received} of the record the relays took, null when
   * none. The rest keys of the invoice, for an order of the app's sale.
   */
  private static String acceptancePublished(PaymentStatus status, long now, Long accepted)
      throws InterruptedException {
    Derive.RestKeys keys =
        Derive.RestKeys.of(APP_KEY, Js.text(APP_DECLARATION, "ref"), ADDRESS, EXPIRES_AT);
    RelayNet net = new RelayNet();
    try {
      List<LocalRelay> relays = new ArrayList<>();
      for (String url : ACCEPTANCE_RELAYS) relays.add(net.add(url));
      if (accepted != null) {
        NostrEvent held =
            Acceptances.acceptanceRecordEvent(keys, accepted, EXPIRES_AT, now - 60, RANDOM);
        // On every relay: a read goes on at the first majority that answered, any of them.
        for (LocalRelay relay : relays) relay.store(held);
      }
      RelayPool pool = new RelayPool(net, 2000, RANDOM, () -> now);
      try {
        Acceptances.publishAcceptance(
            pool,
            status,
            SATS,
            EXPIRES_AT,
            () -> keys,
            ACCEPTANCE_RELAYS,
            () -> now,
            RANDOM,
            QUIET);
      } finally {
        pool.release();
      }
      net.settle();
      Set<String> took = new HashSet<>();
      for (LocalRelay relay : relays) {
        for (NostrEvent event : relay.received) {
          Records.Opened<Long> opened =
              Acceptances.openAcceptanceRecord(event.toJson(), keys, SATS);
          took.add(
              opened == null || opened.isNewer()
                  ? String.valueOf(opened)
                  : opened.value().toString());
        }
      }
      assertTrue(took.size() <= 1, took.toString());
      return took.isEmpty() ? null : took.iterator().next();
    } finally {
      net.close();
    }
  }

  // The merchant's acceptances (the team's records, SPEC.md §31, "The merchant's acceptance"), made
  // by a team of merchant-acceptance.json's own (made: its secret is in the vector) for the app's
  // integration key, so that they check out: at the address of the invoice of the order's sale.

  private static final IntegrationKey TEAM = IntegrationKey.parse(Js.text(MADE, "secretKey"));
  private static final MerchantAcceptances.Keys MERCHANT_KEYS =
      MerchantAcceptances.Keys.of(
          TEAM.key(), TEAM.teamPublicKey(), SALE_AUTHOR, ADDRESS, EXPIRES_AT);

  /** The merchant's relays of the vector: 3, of which 2 are a majority. */
  private static final List<String> MERCHANT_RELAYS =
      List.of("wss://merchant-1.test", "wss://merchant-2.test", "wss://merchant-3.test");

  /**
   * The events of a case's merchant's acceptances, sealed and signed by the team: of its {@code
   * received} (a JSON number, whatever it is), or of version 2 for one of a newer format, dated its
   * {@code createdAt}. Where the vector gives two of one date ids, made again (other random bytes)
   * until their ids sort as the vector's do (the lowest counts).
   */
  private static List<NostrEvent> acceptanceEvents(String name, List<JsonValue> records) {
    RandomBytes random = FixedRandom.of("merchant's acceptance: " + name);
    while (true) {
      List<NostrEvent> events = new ArrayList<>();
      for (JsonValue record : records) {
        JsonObject value =
            has(record, "newer")
                ? Js.obj("version", Constants.MERCHANT_ACCEPTANCE_VERSION + 1)
                : Js.obj(
                    "version",
                    Constants.MERCHANT_ACCEPTANCE_VERSION,
                    "received",
                    Long.parseLong(Js.text(record, "received")));
        events.add(
            Records.seal(
                Constants.MERCHANT_ACCEPTANCE_RECORD_TYPE,
                value,
                Js.hex(MADE, "teamSecret"),
                MERCHANT_KEYS.encryptionKey(),
                MERCHANT_KEYS.address(),
                Js.integer(record, "createdAt"),
                List.of(),
                random));
      }
      boolean sorted = true;
      for (int i = 0; i < records.size(); i += 1) {
        for (int j = 0; j < records.size(); j += 1) {
          if (!has(records.get(i), "id") || !has(records.get(j), "id")) continue;
          boolean given =
              Js.text(records.get(i), "id").compareTo(Js.text(records.get(j), "id")) < 0;
          boolean made = events.get(i).id().compareTo(events.get(j).id()) < 0;
          if (given != made) sorted = false;
        }
      }
      if (sorted) return events;
    }
  }

  /**
   * What each of the 3 relays holds of a case's merchant's acceptances: each record on the first
   * {@code sentBy} of them (absent: all).
   */
  private static Map<String, List<NostrEvent>> heldByRelay(JsonValue c) {
    List<JsonValue> records =
        has(c, "merchantAccepted") ? Js.list(c, "merchantAccepted") : List.of();
    List<NostrEvent> events = acceptanceEvents(Js.text(c, "name"), records);
    Map<String, List<NostrEvent>> held = new LinkedHashMap<>();
    for (int index = 0; index < MERCHANT_RELAYS.size(); index += 1) {
      List<NostrEvent> of = new ArrayList<>();
      for (int i = 0; i < records.size(); i += 1) {
        JsonValue record = records.get(i);
        long sentBy = has(record, "sentBy") ? Js.integer(record, "sentBy") : MERCHANT_RELAYS.size();
        if (index < sentBy) of.add(events.get(i));
      }
      held.put(MERCHANT_RELAYS.get(index), of);
    }
    return held;
  }

  private static boolean unreadable(JsonValue c) {
    return Js.get(c, "merchantAccepted") instanceof JsonString;
  }

  /**
   * The check's read of the merchant's acceptance, through the package's count of what the relays
   * sent (MerchantAcceptances.merchantAcceptanceOf), each relay answering with what it holds;
   * "unreadable": fewer than a majority answered (network, as readMerchantAcceptance fails).
   */
  private static CompletableFuture<MerchantAcceptances.Accepted> countedRead(JsonValue c) {
    if (unreadable(c)) {
      return CompletableFuture.failedFuture(
          new NetworkException("Fewer than a majority of the merchant's relays answered"));
    }
    Map<String, List<JsonValue>> answers = new LinkedHashMap<>();
    for (Map.Entry<String, List<NostrEvent>> relay : heldByRelay(c).entrySet()) {
      answers.put(
          relay.getKey(), relay.getValue().stream().<JsonValue>map(NostrEvent::toJson).toList());
    }
    return CompletableFuture.completedFuture(
        MerchantAcceptances.merchantAcceptanceOf(
            answers, MERCHANT_KEYS, SATS, EXPIRES_AT, MERCHANT_RELAYS.size()));
  }

  @Test
  void makesMerchantsAcceptancesThatCheckOutWithTheAppsIntegrationKey() {
    assertArrayEquals(APP_KEY, TEAM.key());
    NostrEvent event =
        acceptanceEvents("check", List.of(Js.obj("received", "30000", "createdAt", 1791378300)))
            .get(0);
    MerchantAcceptances.Opened opened =
        MerchantAcceptances.openMerchantAcceptance(event.toJson(), MERCHANT_KEYS, SATS, EXPIRES_AT);
    assertNotNull(opened);
    assertEquals(MerchantAcceptances.Status.VALID, opened.status());
    assertEquals(30_000, opened.received());
  }

  @TestFactory
  Stream<DynamicTest> onchainPayments() {
    return Js.list(PAYMENTS, "cases").stream()
        .map(c -> dynamicTest(Js.text(c, "name"), () -> runPayment(c, () -> countedRead(c), null)));
  }

  /**
   * The merchant's Bitcoin node of the cases read from it: on the merchant's network, never
   * mempool.space (SPEC.md §31).
   */
  private static final BitcoinNode NODE =
      new BitcoinNode("http://192.168.1.10:3006", BitcoinNode.Fallback.NONE, false);

  /**
   * Every case again, the explorer read the merchant's Bitcoin node (SPEC.md §31): the same
   * answers, at the node's URLs, give the same status; mempool.space is never asked.
   */
  @TestFactory
  Stream<DynamicTest> onchainPaymentsFromTheMerchantsNode() {
    return Js.list(PAYMENTS, "cases").stream()
        .map(c ->
            dynamicTest(
                "from the merchant's node: " + Js.text(c, "name"),
                () -> runPayment(c, () -> countedRead(c), NODE)));
  }

  /**
   * The cases with merchant's acceptances again, end to end: the merchant's 3 relays, local, each
   * storing the events heldByRelay says as they are (several at one address); the check reads them
   * on every relay (MerchantAcceptances.readMerchantAcceptance). The first, which holds every
   * record, answers 150 ms after the others: a read that went on with the first majority to answer
   * would miss what it holds. "unreadable": the 2 others never answer the read.
   */
  @TestFactory
  Stream<DynamicTest> onchainPaymentsEndToEnd() {
    return Js.list(PAYMENTS, "cases").stream()
        .filter(c -> has(c, "merchantAccepted"))
        .map(c ->
            dynamicTest(
                "end to end on 3 local relays: " + Js.text(c, "name"),
                () -> runPaymentOnRelays(c)));
  }

  private static void runPaymentOnRelays(JsonValue c) throws Exception {
    Map<String, List<NostrEvent>> held = unreadable(c) ? null : heldByRelay(c);
    long now = Js.integer(c, "now");
    RelayNet net = new RelayNet();
    try {
      for (int index = 0; index < MERCHANT_RELAYS.size(); index += 1) {
        String url = MERCHANT_RELAYS.get(index);
        LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
        if (index == 0) behaviour.queryDelayMs = 150;
        else if (held == null) behaviour.mute = true;
        LocalRelay relay = net.add(url, behaviour);
        if (held != null) for (NostrEvent event : held.get(url)) relay.store(event);
      }
      RelayPool pool = new RelayPool(net, 500, RANDOM, () -> now);
      try {
        runPayment(
            c,
            () ->
                MerchantAcceptances.readMerchantAcceptance(
                    pool, MERCHANT_KEYS, SATS, EXPIRES_AT, MERCHANT_RELAYS, RANDOM),
            null);
      } finally {
        pool.release();
      }
      net.settle();
      boolean read = false;
      for (String url : MERCHANT_RELAYS) {
        // Nothing published there: a check never publishes a merchant's acceptance, nor again.
        assertEquals(List.of(), net.get(url).received, url);
        read |= !net.get(url).queries.isEmpty();
      }
      // Read on every relay, as the check reads it: each relay asked once, by its address alone.
      if (held != null && read) {
        JsonObject filter =
            Js.obj(
                "kinds",
                Js.arr(Constants.RECORD_KIND),
                "authors",
                Js.arr(MERCHANT_KEYS.team()),
                "#d",
                Js.arr(MERCHANT_KEYS.address()));
        for (String url : MERCHANT_RELAYS) {
          assertEquals(
              List.of(Js.write(filter)),
              net.get(url).queries.stream().map(Js::write).toList(),
              url);
        }
      }
    } finally {
      net.close();
    }
  }

  /**
   * One case through the package's payment check (Payments.checkOnchainPart, combineParts), with
   * {@code readMerchantAcceptance} as the check's read of the merchant's acceptance; then the
   * acceptance record it publishes (Acceptances.publishAcceptance).
   */
  private static void runPayment(
      JsonValue c,
      java.util.function.Supplier<CompletableFuture<MerchantAcceptances.Accepted>>
          readMerchantAcceptance,
      BitcoinNode node)
      throws Exception {
    // The explorer read: mempool.space, or the node at its URL.
    String explorerUrl = node == null ? Constants.MEMPOOL_URL : NodeUrl.parse(node.url()).rest();
    List<String> warnings = new CopyOnWriteArrayList<>();
    boolean[] readIt = {false};
    List<String> asked = new CopyOnWriteArrayList<>();
    List<String> unexpected = new CopyOnWriteArrayList<>();
    List<JsonValue> published = new CopyOnWriteArrayList<>();
    JsonValue explorer = Js.get(c, "explorer");
    JsonValue blockcypher = Js.get(c, "blockcypher");
    FakeHttp fake =
        new FakeHttp(uri -> {
          String path = uri.toString();
          if (path.equals(explorerUrl + "/address/" + ADDRESS + "/txs")) {
            return has(explorer, "error")
                ? FakeHttp.Answer.text(500, "down")
                : FakeHttp.Answer.json(Js.get(explorer, "txs"));
          }
          if (path.equals(explorerUrl + "/blocks/tip/height")) {
            return has(explorer, "error")
                ? FakeHttp.Answer.text(500, "down")
                : text(Js.get(explorer, "height"));
          }
          if (uri.getHost().equals("api.blockcypher.com")) {
            String[] segments = uri.getPath().split("/");
            String txid = segments[segments.length - 1];
            asked.add(txid);
            if (has(blockcypher, "error")) {
              return FakeHttp.Answer.json(500, Js.obj("error", "down"));
            }
            JsonValue received = Js.get(blockcypher, txid);
            return received == null
                ? FakeHttp.Answer.json(404, Js.obj("error", "Transaction " + txid + " not found."))
                : FakeHttp.Answer.json(
                    Js.obj("hash", txid, "received", received, "confirmations", 0));
          }
          unexpected.add("Not asked in this vector: " + path);
          return FakeHttp.Answer.text(500, "not asked");
        });
    try {
      AtomicInteger reads = new AtomicInteger();
      JsonValue first = Js.get(c, "witnesses");
      // The read after publishing: the vector's witnesses again, unless it says, and those the
      // check published, as the relays then hold them.
      boolean againGiven = has(c, "witnessesAgain");
      JsonValue again = againGiven ? Js.get(c, "witnessesAgain") : first;
      List<JsonValue> publishedEvents = new CopyOnWriteArrayList<>();
      // The rest part: the invoices of the rest the relays hold, and the Lightning part of each.
      Payments.RestPart rests = null;
      if (has(c, "rests")) {
        List<Rests.RestInvoice> invoices = new ArrayList<>();
        List<Tasks.Settled<Lnurl.LightningPart>> results = new ArrayList<>();
        for (JsonValue rest : Js.list(c, "rests")) {
          Bolt11 decoded = Bolt11.decode(Js.text(rest, "invoice"));
          assertNotNull(decoded);
          // What the vector writes of it is the invoice's own.
          assertEquals(
              Js.obj(
                  "paymentHash", Js.text(rest, "paymentHash"),
                  "sats", Js.text(rest, "sats"),
                  "createdAt", Js.integer(rest, "createdAt"),
                  "expiresAt", Js.integer(rest, "expiresAt")),
              Js.obj(
                  "paymentHash", decoded.paymentHash(),
                  "sats", Long.toString(decoded.amountMsat() / 1000),
                  "createdAt", decoded.timestamp(),
                  "expiresAt", decoded.expiresAt()));
          invoices.add(
              new Rests.RestInvoice(
                  Js.text(rest, "invoice"),
                  decoded.paymentHash(),
                  "https://breez.tips/lnurlp/verify/" + decoded.paymentHash(),
                  decoded.amountMsat() / 1000,
                  decoded.timestamp(),
                  decoded.expiresAt()));
          results.add(settledLightning(Js.get(rest, "lightning")));
        }
        rests = Payments.RestPart.of(invoices, results);
      }
      Payments.RestPart restPart = rests;
      boolean publishFails = Js.bool(c, "publishFails");
      long now = Js.integer(c, "now");
      Payments.OnchainCheck check =
          new Payments.OnchainCheck(
              ADDRESS,
              SATS,
              EXPIRES_AT,
              SALE_AUTHOR,
              () -> now,
              new Http(fake, 1000),
              new CallExplorer(node, new CallExplorer.Failures(), () -> now * 1000),
              everyRelay ->
                  reads.getAndIncrement() == 0
                      ? witnessesOf(first, now, List.of())
                      : witnessesOf(again, now, againGiven ? List.of() : publishedEvents),
              OnchainVectorsTest::witnessAt,
              witness -> {
                if (publishFails) {
                  return CompletableFuture.failedFuture(
                      new NetworkException("Fewer than a majority took the witness"));
                }
                assertEquals(ADDRESS, witness.address());
                // Of this invoice: its expiresAt; of none: null.
                assertEquals(witness.sale() == null ? null : EXPIRES_AT, witness.expiresAt());
                published.add(
                    Js.obj(
                        "txid",
                        witness.txid(),
                        "sale",
                        witness.sale() == null
                            ? null
                            : witness.sale().equals(SALE_AUTHOR) ? "this" : witness.sale(),
                        "replacement",
                        witness.replacement()));
                publishedEvents.add(
                    Witnesses.witnessEvent(APP_SEEN, witness, now, RANDOM).toJson());
                return CompletableFuture.completedFuture(null);
              },
              warnings::add,
              () -> Payments.restSummary(restPart).paid(),
              () -> Tolerance.toleranceOf(Js.get(c, "tolerance")),
              () -> {
                readIt[0] = true;
                return readMerchantAcceptance.get();
              });
      Tasks.Settled<Payments.OnchainPart> onchain;
      try {
        onchain = Tasks.Settled.ok(Payments.checkOnchainPart(check));
      } catch (SattlemeException e) {
        onchain = Tasks.Settled.failed(e);
      }
      JsonValue lightningValue = Js.get(c, "lightning");
      Tasks.Settled<Lnurl.LightningPart> lightning =
          lightningValue == JsonNull.NULL ? null : settledLightning(lightningValue);
      PaymentStatus status = null;
      JsonValue result;
      try {
        status =
            Payments.combineParts(SATS, EXPIRES_AT, ADDRESS, now, lightning, onchain, restPart);
        result = Model.status(status);
      } catch (SattlemeException e) {
        result = Js.obj("error", e.getCode());
      }
      JsonValue expected = Js.get(c, "expected");
      assertEquals(expected, result);
      assertEquals(List.of(), unexpected);
      boolean failed = has(expected, "error");
      if (!failed) assertEquals(Js.get(c, "published"), Js.of(published));
      assertEquals(Js.get(c, "asked"), Js.of(asked));
      // The acceptance record the check publishes, unless the relays hold one of what it
      // received or less (SPEC.md §31, "Acceptance records"): as the client does it.
      String acceptance =
          failed
              ? null
              : acceptancePublished(
                  status, now, has(c, "accepted") ? Long.parseLong(Js.text(c, "accepted")) : null);
      assertEquals(has(c, "acceptance") ? Js.text(c, "acceptance") : null, acceptance);
      // A merchant's acceptance of a newer format is warned of when the check read it, whatever
      // its date and how many relays sent it.
      boolean newer =
          has(c, "merchantAccepted")
              && !unreadable(c)
              && Js.list(c, "merchantAccepted").stream().anyMatch(r -> has(r, "newer"));
      assertEquals(
          newer && readIt[0] ? 1 : 0,
          warnings.stream()
              .filter(w -> w.matches("(?s).*merchant's acceptance .* newer format.*"))
              .count(),
          warnings.toString());
    } finally {
      fake.shutdown();
    }
  }

  // relay-reads.json

  private static final JsonValue READS = vectors("relay-reads.json");

  @Test
  void hasThePagesLimitAndThePagesOfARead() {
    assertEquals(
        List.of(Js.integer(READS, "limit"), Js.integer(READS, "pages")),
        List.of((long) Constants.COMPLETE_READ_LIMIT, (long) Constants.MAX_PAGES));
  }

  /**
   * What RelayPool reads of one relay: each page through afterCompletePage; a page that fails ends
   * the read incomplete, the first one the relay's failure.
   */
  @TestFactory
  Stream<DynamicTest> relayReads() {
    return Js.list(READS, "cases").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  List<JsonValue> pages = Js.list(c, "pages");
                  Set<String> seen = new HashSet<>();
                  List<Object> kept = new ArrayList<>();
                  List<Object> untils = new ArrayList<>();
                  untils.add(null);
                  Boolean answered = null;
                  Boolean complete = null;
                  for (int page = 1; answered == null; page += 1) {
                    JsonValue answer = pages.get(page - 1);
                    if (answer instanceof JsonString) {
                      answered = page > 1;
                      complete = false;
                      break;
                    }
                    List<Entry> entries = new ArrayList<>();
                    for (JsonValue pair : Js.items(answer)) {
                      List<JsonValue> items = Js.items(pair);
                      // Every event kept: the vector counts each id once, whatever opens.
                      entries.add(
                          Entry.of(
                              new JsonObject()
                                  .put("id", items.get(0))
                                  .put("created_at", items.get(1)),
                              value -> value));
                    }
                    RelayPool.PageEnd next = RelayPool.afterCompletePage(entries, seen, kept, page);
                    if (next.until() == null) {
                      answered = true;
                      complete = next.complete();
                    } else {
                      untils.add(next.until());
                    }
                  }
                  assertEquals(
                      Js.get(c, "expected"),
                      Js.obj(
                          "untils",
                          untils,
                          "answered",
                          answered,
                          "complete",
                          complete,
                          "kept",
                          kept.size()));
                }));
  }

  /** A vector's page: each event as {@code [id, created_at]}, kept whatever it is. */
  private static List<Entry> entriesOf(JsonValue page) {
    List<Entry> entries = new ArrayList<>();
    for (JsonValue pair : Js.items(page)) {
      List<JsonValue> items = Js.items(pair);
      entries.add(
          Entry.of(
              new JsonObject().put("id", items.get(0)).put("created_at", items.get(1)),
              value -> value));
    }
    return entries;
  }

  /**
   * What RelayPool's paged read reads of one relay: each page through afterPagedPage, at most
   * MAX_PAGES; a page that fails ends the read with what it read, the first one the relay's
   * failure. RelayReadTest runs them on a relay as well.
   */
  @TestFactory
  Stream<DynamicTest> relayPagedReads() {
    return Js.list(READS, "paged").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  List<JsonValue> pages = Js.list(c, "pages");
                  Set<String> seen = new HashSet<>();
                  List<Object> kept = new ArrayList<>();
                  List<Object> untils = new ArrayList<>();
                  untils.add(null);
                  boolean answered = true;
                  for (int page = 0; page < Constants.MAX_PAGES; page += 1) {
                    JsonValue answer = pages.get(page);
                    if (answer instanceof JsonString) {
                      answered = page > 0;
                      break;
                    }
                    Long next = RelayPool.afterPagedPage(entriesOf(answer), seen, kept);
                    if (next == null) break;
                    if (page + 1 < Constants.MAX_PAGES) untils.add(next);
                  }
                  assertEquals(
                      Js.get(c, "expected"),
                      Js.obj("untils", untils, "answered", answered, "kept", kept.size()));
                }));
  }

  /** A state of relay-reads.json, as the pool's. */
  private static RelayPool.RelayState stateOf(String state) {
    return RelayPool.RelayState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
  }

  private static List<RelayPool.RelayState> statesOf(JsonValue c) {
    return Js.strings(c, "relays").stream().map(OnchainVectorsTest::stateOf).toList();
  }

  /**
   * What a read on every relay gives from what each relay's read was when it went on: the pool's
   * own decision (RelayPool.readOutcome: only a relay whose read ended answered, never one still
   * reading). The same cases end to end, each relay's read made by a local relay: RelayReadTest.
   */
  @TestFactory
  Stream<DynamicTest> relayReadsOnEveryRelay() {
    return Js.list(READS, "onEveryRelay").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  RelayPool.Outcome outcome = RelayPool.readOutcome(statesOf(c));
                  assertEquals(
                      Js.get(c, "expected"),
                      Js.obj("answered", outcome.answered(), "complete", outcome.complete()));
                }));
  }

  /** Whether a complete read of a list is complete: RelayPool.readOutcome; end to end too. */
  @TestFactory
  Stream<DynamicTest> relayLists() {
    return Js.list(READS, "lists").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () ->
                    assertEquals(
                        Js.bool(c, "expected"), RelayPool.readOutcome(statesOf(c)).complete())));
  }

  // onchain-tolerances.json

  @TestFactory
  Stream<DynamicTest> tolerances() {
    JsonValue vector = vectors("onchain-tolerances.json");
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(vector, "values")) {
      tests.add(
          dynamicTest(
              "reads " + Js.text(c, "name"),
              () ->
                  assertEquals(
                      (int) Js.integer(c, "expected"), Tolerance.toleranceOf(Js.get(c, "value")))));
    }
    for (JsonValue c : Js.list(vector, "accepted")) {
      tests.add(
          dynamicTest(
              "accepts "
                  + Js.text(c, "expected")
                  + " of "
                  + Js.text(c, "sats")
                  + " at "
                  + Js.integer(c, "tolerance"),
              () ->
                  assertEquals(
                      Js.text(c, "expected"),
                      Long.toString(
                          Tolerance.leastAccepted(
                              Long.parseLong(Js.text(c, "sats")),
                              (int) Js.integer(c, "tolerance"))))));
    }
    for (JsonValue c : Js.list(vector, "due")) {
      tests.add(
          dynamicTest(
              "is due "
                  + Js.text(c, "expected")
                  + " of "
                  + Js.text(c, "sats")
                  + " at "
                  + Js.integer(c, "tolerance")
                  + ", "
                  + Js.text(c, "paidByRests")
                  + " paid by rests, accepted "
                  + (has(c, "merchantAccepted") ? Js.text(c, "merchantAccepted") : "none"),
              () ->
                  assertEquals(
                      Js.text(c, "expected"),
                      Long.toString(
                          Tolerance.dueOnchain(
                              Long.parseLong(Js.text(c, "sats")),
                              (int) Js.integer(c, "tolerance"),
                              Long.parseLong(Js.text(c, "paidByRests")),
                              has(c, "merchantAccepted")
                                  ? Long.valueOf(Js.text(c, "merchantAccepted"))
                                  : null)))));
    }
    return tests.stream();
  }

  // earlier-invoices.json

  private static final JsonValue EARLIER = vectors("earlier-invoices.json");
  private static final long EARLIER_SATS = 49_000;
  private static final Checkout.DeclaredPrice EARLIER_PRICE =
      new Checkout.DeclaredPrice(49_000, 0, "SAT");

  /** The records as the sale opens them. {@code d}: the address itself, one per address. */
  private static NostrEvent eventOf(JsonValue event) {
    return new NostrEvent(
        Js.text(event, "id"), "", Js.integer(event, "createdAt"), 30078, List.of(), "", "");
  }

  private static Declarations.Opened declarationOf(JsonValue d) {
    long createdAt = Js.integer(d, "createdAt");
    return new Declarations.Opened(
        eventOf(Js.obj("createdAt", Js.integer(d, "declaredAt"), "id", Js.text(d, "paymentHash"))),
        Js.text(d, "invoice"),
        new Bolt11(
            "bc",
            EARLIER_SATS * 1000,
            createdAt,
            Js.integer(d, "expiresAt") - createdAt,
            Js.text(d, "paymentHash"),
            null),
        "https://example.com/verify",
        EARLIER_PRICE,
        "order",
        Js.integer(d, "declaredAt"),
        Js.bool(d, "rest") ? new Declarations.RestOf("", 0) : null);
  }

  private static Leases.SaleRecord recordOf(JsonValue r) {
    String address = Js.text(r, "address");
    NostrEvent event = eventOf(Js.get(r, "event"));
    if (Js.bool(r, "withdrawn")) return new Leases.SaleWithdrawal(event, address);
    if (Js.bool(r, "newer")) return new Leases.SaleNewer(event, address);
    String invoice = Js.text(r, "invoice");
    return new Leases.SaleLease(
        new Leases.OpenedLease(
            event,
            address,
            new Leases.Terms(
                address,
                EARLIER_SATS,
                invoice,
                invoice == null ? null : "https://example.com/verify",
                Js.integer(r, "createdAt"),
                Js.integer(r, "expiresAt"),
                EARLIER_PRICE,
                "order",
                0,
                has(r, "confirmed") ? Js.bool(r, "confirmed") : null),
            new JsonObject()));
  }

  private static Tasks.Settled<PaymentStatus> statusOf(JsonValue status) {
    if (has(status, "error")) return Tasks.Settled.failed(errorOf(Js.text(status, "error")));
    String received = has(status, "received") ? Js.text(status, "received") : "0";
    return Tasks.Settled.ok(
        switch (Js.text(status, "state")) {
          case "open" -> new PaymentStatus.Open(0, received, null);
          case "unconfirmed" -> new PaymentStatus.Unconfirmed(received, 0, List.of());
          case "paid" -> new PaymentStatus.PaidOnchain(received, List.of());
          default -> throw new IllegalArgumentException(Js.text(status, "state"));
        });
  }

  @TestFactory
  Stream<DynamicTest> earlierInvoices() {
    return Js.list(EARLIER, "cases").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  int list =
                      (int)
                          (has(c, "relays")
                              ? Js.integer(c, "relays")
                              : Js.integer(EARLIER, "relays"));
                  JsonValue expected = Js.get(c, "expected");
                  List<Declarations.Opened> declarations = new ArrayList<>();
                  for (JsonValue d : Js.list(c, "declarations")) declarations.add(declarationOf(d));
                  List<Leases.SaleRecord> records = new ArrayList<>();
                  for (JsonValue r : Js.list(c, "records")) records.add(recordOf(r));
                  List<Sales.SaleDeclaration> declared =
                      Sales.invoicesOf("order", declarations, Sales.newestRecords(records));
                  List<DeclaredInvoice> invoices = new ArrayList<>();
                  List<Object> shown = new ArrayList<>();
                  for (Sales.SaleDeclaration item : declared) {
                    DeclaredInvoice invoice = item.invoice();
                    invoices.add(invoice);
                    shown.add(
                        Js.obj(
                            "paymentHash", invoice.paymentHash(),
                            "onchainAddress", invoice.onchainAddress(),
                            "createdAt", invoice.createdAt(),
                            "declaredAt", invoice.declaredAt()));
                  }
                  assertEquals(Js.get(expected, "invoices"), Js.of(shown));
                  List<Integer> mayBePaid = Sales.mayStillBePaid(invoices, Js.integer(c, "now"));
                  assertEquals(Js.get(expected, "mayBePaid"), Js.of(mayBePaid));
                  // Every one that may still be paid is checked, even alone.
                  assertEquals(Js.get(expected, "checked"), Js.of(mayBePaid));
                  if (mayBePaid.isEmpty()) {
                    assertEquals(false, has(c, "statuses"));
                    assertEquals(JsonNull.NULL, Js.get(expected, "outcome"));
                    return;
                  }
                  // What a read on every relay sent: each event's sentBy, all the relays when
                  // absent; nothing when fewer than a majority answered it.
                  Map<String, Integer> sentBy = new LinkedHashMap<>();
                  for (JsonValue d : Js.list(c, "declarations")) {
                    sentBy.put(
                        Js.text(d, "paymentHash"),
                        (int) (has(d, "sentBy") ? Js.integer(d, "sentBy") : list));
                  }
                  for (JsonValue r : Js.list(c, "records")) {
                    sentBy.put(
                        Js.text(Js.get(r, "event"), "id"),
                        (int) (has(r, "sentBy") ? Js.integer(r, "sentBy") : list));
                  }
                  List<Sales.SaleDeclaration> pending = new ArrayList<>();
                  for (int index : mayBePaid) pending.add(declared.get(index));
                  // As the client decides it: Sales.heldBy.
                  List<Boolean> held =
                      Sales.heldBy(pending, Js.bool(c, "readFails") ? null : sentBy, list);
                  List<JsonValue> statuses = Js.list(c, "statuses");
                  List<Tasks.Settled<PaymentStatus>> settled = new ArrayList<>();
                  for (int index : mayBePaid) settled.add(statusOf(statuses.get(index)));
                  Sales.Outcome outcome = Sales.pendingOutcome(settled, held);
                  JsonValue got;
                  if (outcome instanceof Sales.PaidOutcome paid) {
                    got = Js.obj("orderPaid", mayBePaid.get(paid.index()));
                  } else if (outcome instanceof Sales.FailedOutcome failed) {
                    got = statuses.get(mayBePaid.get(failed.index()));
                  } else if (outcome instanceof Sales.Unread) {
                    got = Js.obj("error", "network");
                  } else {
                    got = Js.obj("handOut", mayBePaid.get(((Sales.HandOut) outcome).index()));
                  }
                  assertEquals(Js.get(expected, "outcome"), got);
                }));
  }
}
