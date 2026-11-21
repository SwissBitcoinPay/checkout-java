package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.crypto.Nip44;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Explorer;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.internal.onchain.Witnesses;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules the audits of the other implementations made explicit (SPEC.md §2, §12, §13, §22, §26,
 * §31), each with a test that fails without it: a record of a newer format never written over, and
 * logged; the clock of the on-chain part and of a rest invoice; absent is not null; an address
 * named by any event of the seen author that checks out; a call cancelled while a step of its
 * blocked fails as a cancellation. Those of JSON, of rate sources' HTTP statuses and of reads are
 * in JsonTest, RatesTest and RelayReadTest, those of the platform's clients in HttpTest and
 * WebSocketTest.
 */
class AuditTest {
  private static final String ORIGIN = "https://pay.example";
  private static final String ADDRESS = Chain.depositAddress(1);
  private static final RandomBytes RANDOM = new RandomBytes(new SecureRandom());

  private TestMerchant merchant;
  private SattlemeClient sattleme;

  @BeforeEach
  void start() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(
        merchant.channelValue(
            100,
            Map.of(
                "relays",
                merchant.keyRelays,
                "checkout",
                Js.obj("origin", ORIGIN),
                "onchainTolerance",
                500)),
        100);
    merchant.publishLot(List.of(ADDRESS), 90);
    sattleme = merchant.clientBuilder().relayTimeoutMs(1000).build();
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  private Invoice createInvoice(String ref) throws InterruptedException {
    Invoice invoice =
        sattleme.createInvoice(
            Order.builder(ref)
                .sats(49_000)
                .durationSeconds(600)
                .note("Order")
                .checkout(true)
                .build());
    assertEquals(ADDRESS, invoice.onchainAddress());
    return invoice;
  }

  /** Every relay of the key takes {@code event}. */
  private void everywhere(NostrEvent event) {
    for (String url : merchant.keyRelays) merchant.net.get(url).take(event);
  }

  /** The events of {@code author} at {@code d} each relay holds now. */
  private List<String> heldAt(String author, String d) throws InterruptedException {
    merchant.net.settle();
    List<String> ids = new ArrayList<>();
    for (String url : merchant.keyRelays) {
      for (JsonValue value : merchant.net.get(url).events) {
        NostrEvent event = Events.checked(value);
        if (event != null && event.pubkey().equals(author) && d.equals(event.tag("d"))) {
          ids.add(event.id());
        }
      }
    }
    return ids;
  }

  /** The invoices breez.tips made of {@code sats}. */
  private List<TestMerchant.MadeInvoice> madeOf(long sats) {
    synchronized (merchant.invoices) {
      return merchant.invoices.values().stream()
          .filter(made -> made.amountMsat().equals(BigInteger.valueOf(sats * 1000)))
          .toList();
    }
  }

  private List<String> newerWarnings() {
    return merchant.warnings.stream().filter(m -> m.contains("newer format")).toList();
  }

  @Nested
  class ARecordOfANewerFormatIsNeverWrittenOverAndLogged {
    @Test
    void aLateTransactionWhoseWitnessNoneIsOfANewerFormatGetsNoneOverIt() throws Exception {
      Invoice invoice = createInvoice("N-1");
      JsonObject tx = Chain.payment(Chain.txid("late"), ADDRESS, 49_000);
      String txid = Js.text(tx, "txid");
      // A witness of a newer format at the witness address of (A, T, none): it names T.
      Derive.SeenKeys seen = Derive.SeenKeys.of(merchant.integrationKey);
      String d = Witnesses.witnessAddress(seen.addressKey(), ADDRESS, txid, null);
      NostrEvent newer =
          Records.seal(
              Constants.WITNESS_RECORD_TYPE,
              Js.obj("version", 2, "of", "a newer version"),
              seen.secretKey(),
              seen.encryptionKey(),
              d,
              merchant.now,
              List.of(
                  List.of("o", Witnesses.orderTag(seen.addressKey(), ADDRESS, null)),
                  List.of("o", Witnesses.addressTag(seen.addressKey(), ADDRESS))),
              RANDOM);
      everywhere(newer);
      // Long after the lease's end, unknown to BlockCypher: late.
      merchant.now = invoice.expiresAt() + 3600;
      merchant.chain.add(ADDRESS, tx);
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
      // No witness none written over it, on any relay.
      assertEquals(List.of(newer.id(), newer.id(), newer.id()), heldAt(seen.author(), d));
      // And logged.
      assertEquals(1, newerWarnings().size(), merchant.warnings.toString());
    }

    @Test
    void aRestRecordOfANewerFormatHoldsItsAddress() throws Exception {
      Invoice invoice = createInvoice("N-2");
      merchant.now += 60;
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("part"), ADDRESS, 20_000));
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertNotNull(open.rest().bolt11());
      TestMerchant.MadeInvoice rest =
          merchant.invoices.values().stream()
              .filter(made -> made.amountMsat().equals(BigInteger.valueOf(29_000_000)))
              .findFirst()
              .orElseThrow();
      // A newer version of the package wrote the record of that rest invoice in its format.
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "N-2", ADDRESS, invoice.expiresAt());
      String d = Derive.restRecordAddress(keys.key(), rest.paymentHash());
      NostrEvent newer =
          Records.seal(
              Constants.REST_RECORD_TYPE,
              Js.obj("version", 2, "of", "a newer version"),
              keys.secretKey(),
              keys.encryptionKey(),
              d,
              merchant.now + 1,
              List.of(
                  List.of(
                      "expiration",
                      Long.toString(invoice.expiresAt() + Constants.CHECKOUT_LIFE_SECONDS))),
              RANDOM);
      everywhere(newer);
      merchant.now += 10;
      PaymentStatus.Open again =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      // Never written over: every relay still holds it there.
      assertEquals(List.of(newer.id(), newer.id(), newer.id()), heldAt(keys.author(), d));
      // A newer version recorded the invoice: known, still shown, never asked again.
      assertEquals(open.rest(), again.rest());
      assertEquals(1, madeOf(29_000).size());
      assertEquals(1, newerWarnings().size(), merchant.warnings.toString());
    }

    @Test
    void anAcceptanceRecordOfANewerFormatIsKnownNeverWrittenOverAndLogged() throws Exception {
      Invoice invoice = createInvoice("N-3");
      merchant.now += 60;
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "N-3", ADDRESS, invoice.expiresAt());
      String d = Derive.acceptanceRecordAddress(keys.key());
      NostrEvent newer =
          Records.seal(
              Constants.ACCEPTANCE_RECORD_TYPE,
              Js.obj("version", 2, "of", "a newer version"),
              keys.secretKey(),
              keys.encryptionKey(),
              d,
              merchant.now,
              List.of(
                  List.of(
                      "expiration",
                      Long.toString(invoice.expiresAt() + Constants.CHECKOUT_LIFE_SECONDS))),
              RANDOM);
      everywhere(newer);
      merchant.now += 10;
      // Within the tolerance: an acceptance record would be published.
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("least"), ADDRESS, 47_000));
      assertEquals("unconfirmed", sattleme.getStatus(invoice).state());
      assertEquals(List.of(newer.id(), newer.id(), newer.id()), heldAt(keys.author(), d));
      assertEquals(1, newerWarnings().size(), merchant.warnings.toString());
    }

    @Test
    void aLeaseOfANewerFormatHoldsItsAddressAndIsLogged() throws Exception {
      // Another order's lease, written by a newer version: the address is held.
      Derive.Sale other =
          Derive.Sale.of(
              merchant.integrationKey,
              new Derive.Registry(merchant.registryKey, merchant.reader),
              "ANOTHER");
      String d = Leases.leaseAddress(merchant.registryKey, ADDRESS);
      everywhere(
          Records.seal(
              Constants.LEASE_RECORD_TYPE,
              Js.obj("version", 2, "of", "a newer version"),
              other.secretKey,
              Derive.outerKey(merchant.registryKey, d, other.author),
              d,
              merchant.now,
              List.of(),
              RANDOM));
      Invoice invoice = sattleme.createInvoice(Order.builder("N-4").sats(49_000).build());
      assertNull(invoice.onchainAddress());
      assertEquals(1, newerWarnings().size(), merchant.warnings.toString());
    }
  }

  @Nested
  class TheClock {
    @Test
    void ofTheOnchainPartIsReadOnceTheExplorerListedTheTransactions() throws Exception {
      Invoice invoice = createInvoice("C-1");
      long end = invoice.expiresAt() + 120;
      // The lease's last second, the Lightning part final (it is checked at once).
      merchant.now = end;
      JsonObject tx = Chain.payment(Chain.txid("at the end"), ADDRESS, 49_000);
      merchant.chain.add(ADDRESS, tx);
      // The explorer answers 10 s later, the lease over: listed then, it was broadcast by then,
      // which is after the lease's end, not before it. BlockCypher does not know it: late.
      AtomicBoolean listed = new AtomicBoolean();
      merchant.override =
          uri -> {
            if (uri.toString().endsWith("/address/" + ADDRESS + "/txs")
                && listed.compareAndSet(false, true)) {
              merchant.now = end + 10;
            }
            return null;
          };
      assertEquals(new PaymentStatus.Expired("0"), sattleme.getStatus(invoice));
      assertEquals(1, merchant.chain.blockcypherAsked().size());
    }

    @Test
    void ofARestInvoiceIsReadWhenDecidingForItsExpiryItsChecksAndItsRecords() throws Exception {
      Invoice invoice = createInvoice("C-2");
      merchant.now += 60;
      merchant.chain.add(ADDRESS, Chain.payment(Chain.txid("part"), ADDRESS, 20_000));
      long checked = merchant.now;
      // The pay request asked for the rest answers 30 s later, the invoice 20 s after that: the
      // check decides 30 s after its status's time.
      AtomicBoolean asked = new AtomicBoolean();
      AtomicBoolean invoiced = new AtomicBoolean();
      merchant.override =
          uri -> {
            if (uri.toString().equals(TestMerchant.PAY_URL) && asked.compareAndSet(false, true)) {
              merchant.now += 30;
            }
            if (uri.toString().startsWith(TestMerchant.CALLBACK)
                && invoiced.compareAndSet(false, true)) {
              merchant.now += 20;
            }
            return null;
          };
      long deciding = checked + 30;
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertNotNull(open.rest().bolt11());
      assertTrue(asked.get());
      TestMerchant.MadeInvoice rest =
          merchant.invoices.values().stream()
              .filter(made -> made.amountMsat().equals(BigInteger.valueOf(29_000_000)))
              .findFirst()
              .orElseThrow();
      // Its expiry from the clock read when deciding, as its records' dates: neither the time of
      // the check's status, nor one read later.
      assertTrue(invoiced.get());
      assertEquals(invoice.expiresAt() - deciding, rest.expiry());
      Derive.Sale sale =
          Derive.Sale.of(
              merchant.integrationKey,
              new Derive.Registry(merchant.registryKey, merchant.reader),
              "C-2");
      Derive.RestKeys keys =
          Derive.RestKeys.of(merchant.integrationKey, "C-2", ADDRESS, invoice.expiresAt());
      String declared = Derive.registryAddress(merchant.registryKey, rest.paymentHash());
      String recorded = Derive.restRecordAddress(keys.key(), rest.paymentHash());
      merchant.net.settle();
      List<Long> declarations = new ArrayList<>();
      List<Long> records = new ArrayList<>();
      for (JsonValue value : merchant.net.get(merchant.keyRelays.get(0)).events) {
        NostrEvent event = Events.checked(value);
        if (event == null) continue;
        if (event.pubkey().equals(sale.author) && declared.equals(event.tag("d"))) {
          declarations.add(event.createdAt());
        }
        if (event.pubkey().equals(keys.author()) && recorded.equals(event.tag("d"))) {
          records.add(event.createdAt());
        }
      }
      assertEquals(List.of(deciding), declarations, "the declaration's date");
      assertEquals(List.of(deciding), records, "the rest record's date");
    }
  }

  @Nested
  class AbsentIsNotNull {
    @Test
    void aStoredInvoiceMayLackOnlyItsAddress() throws Exception {
      Invoice invoice = sattleme.createInvoice(Order.builder("S-1").sats(500).build());
      JsonObject json = (JsonObject) Js.parse(invoice.toJson());
      for (String name : List.of("bolt11", "paymentHash", "verifyUrl")) {
        JsonObject without = new JsonObject();
        for (Map.Entry<String, JsonValue> entry : json.entries()) {
          if (!entry.getKey().equals(name)) without.put(entry.getKey(), entry.getValue());
        }
        InvalidOrderException error =
            assertThrows(
                InvalidOrderException.class,
                () -> InvoiceToCheck.fromJson(Js.write(without)),
                name);
        assertEquals("invoice", error.getParam());
      }
      JsonObject withoutAddress = new JsonObject();
      for (Map.Entry<String, JsonValue> entry : json.entries()) {
        if (!entry.getKey().equals("onchainAddress")) {
          withoutAddress.put(entry.getKey(), entry.getValue());
        }
      }
      assertEquals(
          "open", sattleme.getStatus(InvoiceToCheck.fromJson(Js.write(withoutAddress))).state());
    }

    @Test
    void aStoredInvoicesRefIsReadOnlyWithAnAddress() throws Exception {
      // Without an address, no rule reads its ref: whatever it holds, the invoice is checked.
      Invoice lightning = sattleme.createInvoice(Order.builder("S-2").sats(500).build());
      JsonObject json = (JsonObject) Js.parse(lightning.toJson());
      assertEquals(
          "open",
          sattleme.getStatus(InvoiceToCheck.fromJson(Js.write(Js.with(json, "ref", 42)))).state());
      // With an address, it is a reference: the witnesses, the lease and the rest invoices of the
      // invoice are its order's sale's.
      JsonObject onchain = (JsonObject) Js.parse(createInvoice("S-3").toJson());
      for (Object ref : java.util.Arrays.asList(42, null, "S 3")) {
        String stored = Js.write(Js.with(onchain, "ref", ref));
        InvalidOrderException error =
            assertThrows(
                InvalidOrderException.class,
                () -> sattleme.getStatus(InvoiceToCheck.fromJson(stored)),
                String.valueOf(ref));
        assertEquals("invoice", error.getParam());
      }
      assertEquals("open", sattleme.getStatus(InvoiceToCheck.fromJson(Js.write(onchain))).state());
    }

    @Test
    void anExplorersOutputWhoseAddressIsNullIsAFailure() {
      JsonObject tx = Chain.payment(Chain.txid("null"), ADDRESS, 1000);
      assertNotNull(Explorer.parseTransaction(tx));
      JsonObject output = Js.obj("value", 1000, "scriptpubkey_address", null);
      assertNull(Explorer.parseTransaction(Js.with(tx, "vout", List.of(output))));
      // Absent: an output to no address (OP_RETURN), as Esplora writes it.
      assertNotNull(Explorer.parseTransaction(Js.with(tx, "vout", List.of(Js.obj("value", 0)))));
    }

    @Test
    void aLeasesInsideWithoutInvoiceAndVerifyUrlDoesNotOpenAsTheSales() {
      Derive.Sale sale =
          Derive.Sale.of(
              merchant.integrationKey,
              new Derive.Registry(merchant.registryKey, merchant.reader),
              "L-1");
      String d = Leases.leaseAddress(merchant.registryKey, ADDRESS);
      JsonObject inside =
          Js.obj(
              "version",
              1,
              "address",
              ADDRESS,
              "sats",
              1000,
              "invoice",
              null,
              "verifyUrl",
              null,
              "createdAt",
              merchant.now,
              "expiresAt",
              merchant.now + 900,
              "sale",
              Js.obj(
                  "key", me.sattle.checkout.internal.Hex.encode(sale.key),
                  "fiat", Js.obj("minor", 1000, "digits", 0, "currency", "SAT"),
                  "note", "L-1"),
              "taker",
              sale.taker,
              "confirmed",
              true);
      assertNotNull(openInside(sale, d, inside));
      JsonObject withoutBoth = new JsonObject();
      for (Map.Entry<String, JsonValue> entry : inside.entries()) {
        if (!entry.getKey().equals("invoice") && !entry.getKey().equals("verifyUrl")) {
          withoutBoth.put(entry.getKey(), entry.getValue());
        }
      }
      assertNull(openInside(sale, d, withoutBoth));
    }

    private Leases.OpenedLease openInside(Derive.Sale sale, String d, JsonObject inside) {
      String sealed = Nip44.encrypt(Js.write(inside), sale.innerKey, RANDOM.next(32));
      return Leases.openLeaseInside(
          new Leases.Outside(Leases.Kind.LEASE, merchant.now + 900 + 120, sealed), d, sale);
    }

    @Test
    void aWitnessWithoutSaleDoesNotOpen() {
      Derive.SeenKeys seen = Derive.SeenKeys.of(merchant.integrationKey);
      String txid = Chain.txid("w");
      Witnesses.Witness witness =
          new Witnesses.Witness(ADDRESS, txid, null, 1000, List.of(), false, null);
      NostrEvent made = Witnesses.witnessEvent(seen, witness, merchant.now, RANDOM);
      assertNotNull(Witnesses.openWitness(made.toJson(), seen));
      NostrEvent without =
          Records.seal(
              Constants.WITNESS_RECORD_TYPE,
              Js.obj(
                  "version",
                  1,
                  "address",
                  ADDRESS,
                  "txid",
                  txid,
                  "sats",
                  1000,
                  "inputs",
                  List.of(),
                  "replacement",
                  false,
                  "expiresAt",
                  null),
              seen.secretKey(),
              seen.encryptionKey(),
              Witnesses.witnessAddress(seen.addressKey(), ADDRESS, txid, null),
              merchant.now,
              made.tags().subList(1, made.tags().size()),
              RANDOM);
      assertNull(Witnesses.openWitness(without.toJson(), seen));
    }
  }

  @Test
  void namesAnAddressByAnyEventOfTheSeenAuthorThatChecksOutWithItsTag() throws Exception {
    Derive.SeenKeys seen = Derive.SeenKeys.of(merchant.integrationKey);
    List<List<String>> tags =
        List.of(List.of("o", Witnesses.addressTag(seen.addressKey(), ADDRESS)));
    // Not a witness (its content no record), but the seen author's and checking out: it names A.
    NostrEvent named =
        Events.sign(30078, merchant.now, tags, "not a witness", seen.secretKey(), RANDOM);
    // The same with a signature that does not check out names nothing.
    JsonObject forged =
        Js.with(
            Events.sign(30078, merchant.now, tags, "forged", seen.secretKey(), RANDOM).toJson(),
            "sig",
            "00".repeat(64));
    for (String url : merchant.keyRelays) merchant.net.get(url).store(forged);
    assertEquals(
        ADDRESS,
        sattleme.createInvoice(Order.builder("A-1").sats(49_000).build()).onchainAddress());
    everywhere(named);
    merchant.publishLot(List.of(ADDRESS, Chain.depositAddress(2)), 91);
    for (int i = 0; i < 5; i += 1) {
      Invoice invoice = sattleme.createInvoice(Order.builder("A-" + (i + 2)).sats(49_000).build());
      // Never A again (held, then named), whichever order the free ones are tried in.
      assertTrue(!ADDRESS.equals(invoice.onchainAddress()), invoice.onchainAddress());
    }
  }

  @Test
  void failsACancelledCallAsACancellationEvenWhenAStepOfItsBlocked() throws Exception {
    // A stored invoice whose fields take time to give (the shop's database): the thread is
    // interrupted meanwhile, then its check fails. The call was cancelled: InterruptedException.
    InvoiceToCheck slow =
        new InvoiceToCheck() {
          @Override
          public String ref() {
            return "X-1";
          }

          @Override
          public String bolt11() {
            long until = System.nanoTime() + 300_000_000L;
            while (System.nanoTime() < until) {
              Thread.onSpinWait();
            }
            return "not an invoice";
          }

          @Override
          public String paymentHash() {
            return "00".repeat(32);
          }

          @Override
          public String verifyUrl() {
            return "https://breez.tips/lnurlp/verify/x";
          }

          @Override
          public String onchainAddress() {
            return null;
          }

          @Override
          public String sats() {
            return "1";
          }

          @Override
          public long expiresAt() {
            return 1;
          }
        };
    List<Throwable> thrown = new CopyOnWriteArrayList<>();
    Thread thread =
        new Thread(() -> {
          try {
            sattleme.getStatus(slow);
          } catch (Throwable e) {
            thrown.add(e);
          }
        });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5000);
    assertEquals(1, thrown.size());
    assertInstanceOf(InterruptedException.class, thrown.get(0), String.valueOf(thrown.get(0)));
  }

  @Test
  void keepsCheckoutLinksOfVersion2ForAnInvoiceWithAnAddress() throws Exception {
    // Sanity of this class's channel: checkout links and the tolerance read as in the others.
    Invoice invoice = createInvoice("K-1");
    String url = invoice.checkoutUrl();
    assertInstanceOf(Checkout.Link.class, Checkout.parseFragment(url.substring(url.indexOf('#'))));
    LocalRelay relay = merchant.net.get(merchant.keyRelays.get(0));
    assertTrue(relay.events.size() > 0);
  }
}
