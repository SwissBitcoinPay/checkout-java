package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Declarations;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.json.JsonNull;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Acceptances;
import me.sattle.checkout.internal.onchain.Rests;
import me.sattle.checkout.support.Js;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The package against vectors/onchain-rests.json (SPEC.md §31, "The rest of an underpayment"), as
 * checkout-node's rest vector tests: the rest keys, the rest records and acceptance records as the
 * page reads them, the declarations of rest invoices as the sale opens them, and when a check asks
 * for an invoice of the rest. checkout-node also checks the file against what its generator builds;
 * the vectors are checkout-spec's, read only here.
 */
class RestVectorsTest {
  private static final JsonValue VECTORS = Js.spec("vectors/onchain-rests.json");
  private static final byte[] KEY = Js.hex(VECTORS, "integrationKey");
  private static final String FIRST_ADDRESS = Js.text(Js.list(VECTORS, "keys").get(0), "address");

  @TestFactory
  Stream<DynamicTest> theRestKeys() {
    return Js.list(VECTORS, "keys").stream()
        .map(c ->
            dynamicTest(
                "the rest keys of "
                    + Js.text(c, "ref")
                    + ", "
                    + Js.text(c, "address")
                    + " and "
                    + Js.integer(c, "expiresAt"),
                () -> {
                  Derive.RestKeys keys =
                      Derive.RestKeys.of(
                          KEY,
                          Js.text(c, "ref"),
                          Js.text(c, "address"),
                          Js.integer(c, "expiresAt"));
                  List<Object> records = new ArrayList<>();
                  for (JsonValue record : Js.list(c, "records")) {
                    String paymentHash = Js.text(record, "paymentHash");
                    records.add(
                        Js.obj(
                            "paymentHash",
                            paymentHash,
                            "address",
                            Derive.restRecordAddress(keys.key(), paymentHash)));
                  }
                  assertEquals(
                      Js.obj(
                          "restKey", Js.text(c, "restKey"),
                          "restSecret", Js.text(c, "restSecret"),
                          "restAuthor", Js.text(c, "restAuthor"),
                          "restEncryptionKey", Js.text(c, "restEncryptionKey"),
                          "records", Js.get(c, "records"),
                          "acceptanceAddress", Js.text(c, "acceptanceAddress")),
                      Js.obj(
                          "restKey", Hex.encode(keys.key()),
                          "restSecret", Hex.encode(keys.secretKey()),
                          "restAuthor", keys.author(),
                          "restEncryptionKey", Hex.encode(keys.encryptionKey()),
                          "records", records,
                          "acceptanceAddress", Derive.acceptanceRecordAddress(keys.key())));
                }));
  }

  @TestFactory
  Stream<DynamicTest> restRecords() {
    JsonValue records = Js.get(VECTORS, "records");
    JsonValue link = Js.get(records, "link");
    return Js.list(records, "cases").stream()
        .map(c ->
            dynamicTest(
                "record: " + Js.text(c, "name"),
                () -> {
                  Derive.RestKeys keys =
                      Derive.RestKeys.of(
                          KEY, "ORD-1042", FIRST_ADDRESS, Js.integer(link, "expiresAt"));
                  assertEquals(Js.text(records, "restKey"), Hex.encode(keys.key()));
                  // As the page reads it: the rest key and author of the link, no secret.
                  Rests.OpenedRest opened =
                      Rests.openRestRecord(
                          Js.get(c, "event"),
                          Derive.RestKeys.reader(
                              Js.hex(records, "restKey"), Js.text(records, "restAuthor")),
                          Long.parseLong(Js.text(link, "sats")),
                          Js.integer(link, "createdAt"),
                          Js.integer(link, "expiresAt"));
                  JsonValue got =
                      opened == null
                          ? JsonNull.NULL
                          : opened.isNewer()
                              ? new JsonString("newer")
                              : Js.obj(
                                  "invoice", opened.invoice().bolt11(),
                                  "paymentHash", opened.invoice().paymentHash(),
                                  "verifyUrl", opened.invoice().verifyUrl(),
                                  "sats", Long.toString(opened.invoice().sats()));
                  assertEquals(Js.get(c, "expected"), got);
                }));
  }

  @TestFactory
  Stream<DynamicTest> acceptanceRecords() {
    JsonValue acceptances = Js.get(VECTORS, "acceptances");
    JsonValue link = Js.get(Js.get(VECTORS, "records"), "link");
    return Js.list(acceptances, "cases").stream()
        .map(c ->
            dynamicTest(
                "acceptance: " + Js.text(c, "name"),
                () -> {
                  Derive.RestKeys keys =
                      Derive.RestKeys.of(
                          KEY, "ORD-1042", FIRST_ADDRESS, Js.integer(link, "expiresAt"));
                  assertEquals(Js.text(acceptances, "restKey"), Hex.encode(keys.key()));
                  assertEquals(Js.text(acceptances, "restAuthor"), keys.author());
                  Records.Opened<Long> opened =
                      Acceptances.openAcceptanceRecord(
                          Js.get(c, "event"),
                          keys,
                          Long.parseLong(Js.text(Js.get(acceptances, "link"), "sats")));
                  JsonValue got =
                      opened == null
                          ? JsonNull.NULL
                          : opened.isNewer()
                              ? new JsonString("newer")
                              : Js.obj("received", Long.toString(opened.value()));
                  assertEquals(Js.get(c, "expected"), got);
                }));
  }

  @TestFactory
  Stream<DynamicTest> declarationsOfRestInvoices() {
    JsonValue declarations = Js.get(VECTORS, "declarations");
    Derive.Sale sale =
        Derive.Sale.of(
            KEY,
            new Derive.Registry(
                Js.hex(declarations, "registryKey"), Js.text(declarations, "registryReader")),
            Js.text(declarations, "ref"));
    return Js.list(declarations, "cases").stream()
        .map(c ->
            dynamicTest(
                "declaration: " + Js.text(c, "name"),
                () -> {
                  Declarations.Opened opened =
                      Declarations.openDeclaration(Js.get(c, "event"), sale);
                  JsonValue got =
                      opened == null
                          ? JsonNull.NULL
                          : Js.obj(
                              "invoice",
                              opened.invoice(),
                              "note",
                              opened.note(),
                              "rest",
                              opened.rest() == null
                                  ? null
                                  : Js.obj(
                                      "address",
                                      opened.rest().address(),
                                      "expiresAt",
                                      opened.rest().expiresAt()));
                  assertEquals(Js.get(c, "expected"), got);
                }));
  }

  @TestFactory
  Stream<DynamicTest> asking() {
    return Js.list(VECTORS, "asking").stream()
        .map(c ->
            dynamicTest(
                "asking: " + Js.text(c, "name"),
                () -> {
                  List<Rests.RestInvoice> invoices = new ArrayList<>();
                  Set<String> paid = new HashSet<>();
                  for (JsonValue rest : Js.list(c, "rests")) {
                    // Its bolt11: its id.
                    invoices.add(
                        new Rests.RestInvoice(
                            Js.text(rest, "id"),
                            Js.text(rest, "paymentHash"),
                            "https://breez.tips/verify",
                            Long.parseLong(Js.text(rest, "sats")),
                            Js.integer(rest, "createdAt"),
                            Js.integer(rest, "expiresAt")));
                    if (Js.bool(rest, "paid")) paid.add(Js.text(rest, "paymentHash"));
                  }
                  long sats = Long.parseLong(Js.text(c, "sats"));
                  long expiresAt = Js.integer(c, "expiresAt");
                  long now = Js.integer(c, "now");
                  PaymentStatus.Rest rest =
                      Rests.restOf(
                          sats,
                          expiresAt,
                          FIRST_ADDRESS,
                          Long.parseLong(Js.text(c, "received")),
                          now,
                          invoices,
                          paid);
                  JsonValue lease = Js.get(c, "lease");
                  Rests.RestAsk ask =
                      Rests.restToAsk(
                          rest,
                          sats,
                          expiresAt,
                          now,
                          lease == JsonNull.NULL ? null : Long.parseLong(Js.text(lease, "sats")),
                          lease == JsonNull.NULL ? null : Js.integer(lease, "expiresAt"));
                  JsonValue sendable = Js.get(c, "sendable");
                  if (ask != null
                      && !Rests.sendable(
                          ask.sats(),
                          Long.parseLong(Js.text(sendable, "min")),
                          Long.parseLong(Js.text(sendable, "max")))) {
                    ask = null;
                  }
                  assertEquals(
                      Js.get(c, "expected"),
                      Js.obj(
                          "rest",
                          rest == null
                              ? null
                              : Js.obj("sats", rest.sats(), "invoice", rest.bolt11()),
                          "ask",
                          ask == null
                              ? null
                              : Js.obj(
                                  "sats",
                                  Long.toString(ask.sats()),
                                  "expirySeconds",
                                  ask.expirySeconds())));
                }));
  }
}
