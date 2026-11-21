package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.crypto.Curve;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Records;
import me.sattle.checkout.internal.onchain.Acceptances;
import me.sattle.checkout.internal.onchain.MerchantAcceptances;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.RelayNet;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The merchant's acceptance (SPEC.md §31, "The merchant's acceptance"), end to end against local
 * relays and fakes of breez.tips and of the explorers: the team's record, read on every relay with
 * the witnesses, pays an order underpaid on-chain down to what the merchant accepted, only when a
 * majority of the merchant's relays hold it, whichever majority answers; the pages of the order's
 * checkout links told by an acceptance record (the server's); the check publishing nothing of it.
 * As checkout-node's merchant's acceptance tests.
 */
class MerchantAcceptanceTest {
  private static final String ORIGIN = "https://pay.example";
  private static final String ADDRESS = Chain.depositAddress(1);
  private static final long SATS = 49_000;

  /** What came on-chain, and what the merchant accepted. */
  private static final long CAME = 30_000;

  /** No {@code onchainTolerance} in the channel. */
  private static final Integer NO_TOLERANCE = null;

  private TestMerchant merchant;
  private SattlemeClient sattleme;
  private Invoice invoice;
  private JsonObject tx;

  @BeforeEach
  void start() throws Exception {
    startWith(NO_TOLERANCE, CAME);
  }

  /** The merchant, its channel of {@code tolerance}, an invoice, and {@code came} on-chain. */
  private void startWith(Integer tolerance, long came) throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishLot(List.of(ADDRESS), 90);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("relays", merchant.keyRelays);
    fields.put("checkout", Js.obj("origin", ORIGIN));
    if (tolerance != null) fields.put("onchainTolerance", tolerance);
    merchant.publishChannel(merchant.channelValue(100, fields), 100);
    sattleme = merchant.clientBuilder().relayTimeoutMs(1000).build();
    invoice =
        sattleme.createInvoice(
            Order.builder("R-1")
                .sats(SATS)
                .durationSeconds(600)
                .note("Order 1")
                .checkout(true)
                .build());
    assertEquals(ADDRESS, invoice.onchainAddress());
    merchant.now += 60;
    tx = Chain.payment(Chain.txid("came"), ADDRESS, came);
    merchant.chain.add(ADDRESS, tx);
  }

  @AfterEach
  void stop() {
    merchant.stop();
  }

  private String txid() {
    return Js.text(tx, "txid");
  }

  private String saleAuthor(String ref) {
    return Derive.Sale.of(
            merchant.integrationKey,
            new Derive.Registry(merchant.registryKey, merchant.reader),
            ref)
        .author;
  }

  /**
   * Where the merchant's acceptance of the invoice of {@code ref} at {@code address} ending at
   * {@code expiresAt} is.
   */
  private String addressOf(String ref, String address, long expiresAt) {
    return Derive.merchantAcceptanceAddress(
        merchant.integrationKey, saleAuthor(ref), address, expiresAt);
  }

  private String addressOf() {
    return addressOf(invoice.ref(), ADDRESS, invoice.expiresAt());
  }

  /**
   * A merchant's acceptance as the merchant's app makes it: {@code value} sealed with the channel's
   * key at {@code address}, signed by {@code signer}, dated {@code createdAt}.
   */
  private NostrEvent acceptance(JsonValue value, byte[] signer, String address, long createdAt) {
    return Records.seal(
        Constants.MERCHANT_ACCEPTANCE_RECORD_TYPE,
        value,
        signer,
        Derive.channelEncryptionKey(merchant.integrationKey),
        address,
        createdAt,
        List.of(),
        FixedRandom.of("merchant's acceptance " + Js.write(value) + " " + createdAt));
  }

  /** The merchant's acceptance of the invoice at {@code received}, dated {@code createdAt}. */
  private NostrEvent acceptance(long received, long createdAt) {
    return acceptance(
        Js.obj("version", 1, "received", received), merchant.teamSecret, addressOf(), createdAt);
  }

  /** The merchant's acceptance of the invoice at {@code received}, made a minute ago. */
  private NostrEvent acceptance(long received) {
    return acceptance(received, merchant.now - 60);
  }

  private LocalRelay relay(int index) {
    return merchant.net.get(merchant.keyRelays.get(index));
  }

  /** The relays at {@code indexes} of the key's take {@code event}, as a relay keeps it. */
  private void hold(NostrEvent event, int... indexes) {
    for (int index : indexes) relay(index).take(event);
  }

  /** Every relay of the key takes {@code event}. */
  private void hold(NostrEvent event) {
    hold(event, 0, 1, 2);
  }

  /** The relays at {@code indexes} never answer the read of the invoice's merchant's acceptance. */
  private void muteRead(int... indexes) {
    String address = addressOf();
    for (int index = 0; index < merchant.keyRelays.size(); index += 1) {
      LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
      for (int muted : indexes) {
        if (muted == index) {
          behaviour.muteQuery =
              filter ->
                  filter.get("#d") instanceof JsonArray d
                      && d.items().contains(new JsonString(address));
        }
      }
      relay(index).behaviour = behaviour;
    }
  }

  /**
   * The acceptance records of the invoice the relay holds, as a checkout link's page opens them.
   */
  private List<Long> acceptanceRecords(int index) throws InterruptedException {
    // On a majority when the call returned, on the others once their writes ended.
    merchant.net.settle();
    Derive.RestKeys keys =
        Derive.RestKeys.of(merchant.integrationKey, invoice.ref(), ADDRESS, invoice.expiresAt());
    List<Long> found = new ArrayList<>();
    for (JsonValue event : relay(index).events) {
      Records.Opened<Long> opened = Acceptances.openAcceptanceRecord(event, keys, SATS);
      if (opened != null && !opened.isNewer()) found.add(opened.value());
    }
    return found;
  }

  private List<Long> acceptanceRecords() throws InterruptedException {
    return acceptanceRecords(0);
  }

  /** What the check sent the relays of the team's, or at the merchant's acceptance's address. */
  private List<NostrEvent> sentOfTheTeam() throws InterruptedException {
    merchant.net.settle();
    String address = addressOf();
    List<NostrEvent> sent = new ArrayList<>();
    for (int index = 0; index < merchant.keyRelays.size(); index += 1) {
      for (NostrEvent event : relay(index).received) {
        if (event.pubkey().equals(merchant.team) || address.equals(event.tag("d"))) {
          sent.add(event);
        }
      }
    }
    return sent;
  }

  /** The invoices breez.tips made of {@code sats}. */
  private List<TestMerchant.MadeInvoice> madeOf(long sats) {
    synchronized (merchant.invoices) {
      return merchant.invoices.values().stream()
          .filter(made -> made.amountMsat().equals(BigInteger.valueOf(sats * 1000)))
          .toList();
    }
  }

  /** The status of the order with 30 000 in time and the rest asked of 19 000. */
  private PaymentStatus underpaid() {
    List<TestMerchant.MadeInvoice> rests = madeOf(SATS - CAME);
    assertEquals(1, rests.size());
    String pr = rests.get(0).pr();
    return new PaymentStatus.Open(
        invoice.expiresAt(),
        Long.toString(CAME),
        new PaymentStatus.Rest(
            Long.toString(SATS - CAME),
            pr,
            "bitcoin:" + ADDRESS + "?amount=0.00019&lightning=" + pr));
  }

  /** {@code status} is that of the order with 30 000 in time and the rest asked of 19 000. */
  private void assertUnderpaid(PaymentStatus status) {
    assertEquals(underpaid(), status);
  }

  private PaymentStatus unconfirmed() {
    return new PaymentStatus.Unconfirmed(Long.toString(CAME), 0, List.of(txid()));
  }

  /** The warnings of a merchant's acceptance of a newer format. */
  private List<String> warnedOfANewerFormat() {
    return merchant.warnings.stream()
        .filter(w -> w.contains("a merchant's acceptance of this order has a newer format"))
        .toList();
  }

  private static void until(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 300 && !condition.getAsBoolean(); i += 1) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  @Nested
  class AMerchantsAcceptanceAMajorityOfTheRelaysHold {
    @Test
    void paysTheOrderDownToItUnconfirmedThenPaidThePagesToldNothingPublishedOfIt()
        throws Exception {
      hold(acceptance(CAME), 0, 1);
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
      // No rest asked: the order is paid.
      assertEquals(List.of(), madeOf(SATS - CAME));
      // The acceptance record (the server's, for the pages of the order's checkout links), on
      // every relay: what was received, nothing of the merchant's acceptance itself.
      for (int index = 0; index < merchant.keyRelays.size(); index += 1) {
        assertEquals(List.of(CAME), acceptanceRecords(index), merchant.keyRelays.get(index));
      }
      // The merchant's acceptance never published again, nor anything of the team's.
      assertEquals(List.of(), sentOfTheTeam());
      for (JsonValue event : relay(2).events) {
        assertFalse(Js.write(event).contains(addressOf()), Js.write(event));
      }

      merchant.chain.confirm(ADDRESS, txid(), merchant.chain.height - 2);
      merchant.now = invoice.expiresAt() + 3600;
      PaymentStatus.PaidOnchain paid =
          new PaymentStatus.PaidOnchain(Long.toString(CAME), List.of(txid()));
      assertEquals(paid, sattleme.getStatus(invoice));
      OrderPaidException error =
          assertThrows(
              OrderPaidException.class,
              () -> sattleme.createInvoice(Order.builder("R-1").sats(SATS).build()));
      assertEquals(paid, error.getStatus());
      assertEquals(List.of(), merchant.warnings);
    }

    static Stream<Arguments> majorities() {
      return Stream.of(
          Arguments.of("the third failing", new int[] {0, 1}, new int[] {2}),
          Arguments.of(
              "the first failing, the 2 others holding it", new int[] {1, 2}, new int[] {0}),
          Arguments.of("every relay answering", new int[] {0, 1}, new int[] {}));
    }

    @ParameterizedTest(name = "counts whichever majority answers: {0}")
    @MethodSource("majorities")
    void countsWhicheverMajorityAnswers(String name, int[] holders, int[] failing)
        throws Exception {
      hold(acceptance(CAME), holders);
      muteRead(failing);
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
    }

    @Test
    void waitsForARelayThatHoldsItAndAnswersLate() throws Exception {
      hold(acceptance(CAME), 0, 1);
      relay(0).behaviour.queryDelayMs = 400;
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
    }

    @Test
    void countsOneMadeBeforeTheEndAfterTheLeasesEndNeverExpired() throws Exception {
      assertUnderpaid(sattleme.getStatus(invoice));
      hold(acceptance(CAME, invoice.expiresAt() - 60), 1, 2);
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
    }

    @Test
    void countsOneOfLessThanWhatCameWhatCamePays() throws Exception {
      hold(acceptance(20_000));
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
      assertEquals(List.of(CAME), acceptanceRecords());
    }

    @Test
    void asksTheWholeRestOfOneOfMoreThanWhatCame() throws Exception {
      hold(acceptance(35_000));
      assertUnderpaid(sattleme.getStatus(invoice));
      assertEquals(List.of(), acceptanceRecords());
    }
  }

  @Nested
  class AMerchantsAcceptanceOnlyAMinorityOfTheRelaysHolds {
    @Test
    void neverCountsWhicheverRelaysAnswerTheRestAskedThenExpired() throws Exception {
      hold(acceptance(CAME), 0);
      assertUnderpaid(sattleme.getStatus(invoice));
      // The relay that holds it and one that does not answer: still a minority's.
      muteRead(2);
      assertUnderpaid(sattleme.getStatus(invoice));
      muteRead();
      merchant.now = invoice.expiresAt() + 60;
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertEquals(null, open.rest());
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(new PaymentStatus.Expired(Long.toString(CAME)), sattleme.getStatus(invoice));
      assertEquals(List.of(), acceptanceRecords());
    }

    @Test
    void isWhatRemainsWhenARelayThatHoldsItFailsAndTheOthersThatDoAreAMinority() throws Exception {
      // SPEC.md §27: that check does not count it; a later one, which that relay answers, does.
      hold(acceptance(CAME), 0, 1);
      muteRead(0);
      assertUnderpaid(sattleme.getStatus(invoice));
      muteRead();
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
    }

    @Test
    void countsARelayOnceWhateverCopiesOfItItSends() throws Exception {
      NostrEvent event = acceptance(CAME);
      // The same event under another signature (BIP-340: any of them verifies): the same record.
      JsonObject resigned =
          Js.with(
              event.toJson(),
              "sig",
              Hex.encode(
                  Curve.sign(Hex.decode(event.id()), merchant.teamSecret, FixedRandom.bytes(77))));
      assertNotEquals(event.sig(), Js.text(resigned, "sig"));
      relay(0).store(event);
      relay(0).store(event);
      relay(0).store(resigned);
      assertUnderpaid(sattleme.getStatus(invoice));
      // The count itself, of what the relays sent (the pool already reads a relay's copies of one
      // id once): one relay, whatever copies it sent; then a second one, a majority.
      MerchantAcceptances.Keys keys =
          MerchantAcceptances.Keys.of(
              merchant.integrationKey,
              merchant.team,
              saleAuthor(invoice.ref()),
              ADDRESS,
              invoice.expiresAt());
      Map<String, List<JsonValue>> answers = new LinkedHashMap<>();
      answers.put(
          "a",
          List.of(
              event.toJson(),
              event.toJson(),
              resigned,
              Js.with(event.toJson(), "sig", Js.text(resigned, "sig"))));
      answers.put("b", List.of());
      answers.put("c", List.of());
      assertEquals(
          MerchantAcceptances.Accepted.NONE,
          MerchantAcceptances.merchantAcceptanceOf(answers, keys, SATS, invoice.expiresAt(), 3));
      answers.put("b", List.of(resigned));
      assertEquals(
          new MerchantAcceptances.Accepted(CAME, false),
          MerchantAcceptances.merchantAcceptanceOf(answers, keys, SATS, invoice.expiresAt(), 3));
    }
  }

  @Nested
  class ARecordThatIsNotThisInvoicesMerchantsAcceptance {
    static Stream<Arguments> others() {
      return Stream.of(
          Arguments.of(
              "of another invoice of the order (another expiresAt)",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("version", 1, "received", CAME),
                          t.merchant.teamSecret,
                          t.addressOf("R-1", ADDRESS, t.invoice.expiresAt() + 3600),
                          t.merchant.now - 60)),
          Arguments.of(
              "of another order",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("version", 1, "received", CAME),
                          t.merchant.teamSecret,
                          t.addressOf("R-2", ADDRESS, t.invoice.expiresAt()),
                          t.merchant.now - 60)),
          Arguments.of(
              "of another address",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("version", 1, "received", CAME),
                          t.merchant.teamSecret,
                          t.addressOf("R-1", Chain.depositAddress(2), t.invoice.expiresAt()),
                          t.merchant.now - 60)),
          Arguments.of(
              "of another team",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("version", 1, "received", CAME),
                          FixedRandom.bytes(9),
                          t.addressOf(),
                          t.merchant.now - 60)),
          Arguments.of(
              "of the whole amount",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("version", 1, "received", SATS),
                          t.merchant.teamSecret,
                          t.addressOf(),
                          t.merchant.now - 60)),
          Arguments.of(
              "of 0 sats",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("version", 1, "received", 0),
                          t.merchant.teamSecret,
                          t.addressOf(),
                          t.merchant.now - 60)),
          Arguments.of(
              "without a version",
              (Function<MerchantAcceptanceTest, NostrEvent>)
                  t ->
                      t.acceptance(
                          Js.obj("received", CAME),
                          t.merchant.teamSecret,
                          t.addressOf(),
                          t.merchant.now - 60)));
    }

    @ParameterizedTest(name = "is not counted: {0}")
    @MethodSource("others")
    void isNotCounted(String name, Function<MerchantAcceptanceTest, NostrEvent> record)
        throws Exception {
      hold(record.apply(MerchantAcceptanceTest.this));
      assertUnderpaid(sattleme.getStatus(invoice));
      assertEquals(List.of(), merchant.warnings);
    }

    @Test
    void isNotCountedWithADamagedSignature() throws Exception {
      NostrEvent event = acceptance(CAME);
      String sig =
          event.sig().substring(0, event.sig().length() - 1)
              + (event.sig().endsWith("0") ? "1" : "0");
      for (int index = 0; index < merchant.keyRelays.size(); index += 1) {
        relay(index).store(Js.with(event.toJson(), "sig", sig));
      }
      assertUnderpaid(sattleme.getStatus(invoice));
    }

    static Stream<Arguments> dates() {
      return Stream.of(
          Arguments.of("at the lease's end: counted", 120, true),
          Arguments.of("a second after the lease's end: not counted", 121, false));
    }

    // Dated after now: its created_at is never compared with now, only with the lease's end.
    @ParameterizedTest(name = "dated {0}")
    @MethodSource("dates")
    void isDated(String name, long after, boolean counted) throws Exception {
      hold(acceptance(CAME, invoice.expiresAt() + after));
      PaymentStatus status = sattleme.getStatus(invoice);
      if (counted) assertEquals(unconfirmed(), status);
      else assertUnderpaid(status);
    }
  }

  @Nested
  class AMerchantsAcceptanceOfANewerFormat {
    /** A record of version 2, dated {@code createdAt}, on every relay with {@code others}. */
    private void newerWith(long createdAt, NostrEvent... others) {
      NostrEvent newer =
          acceptance(Js.obj("version", 2), merchant.teamSecret, addressOf(), createdAt);
      for (int index = 0; index < merchant.keyRelays.size(); index += 1) {
        for (NostrEvent event : others) relay(index).store(event);
        relay(index).store(newer);
      }
    }

    @Test
    void newerThanAValidOneLeavesTheOrderWithoutOneAndIsWarnedOf() throws Exception {
      newerWith(merchant.now - 30, acceptance(CAME, merchant.now - 60));
      assertUnderpaid(sattleme.getStatus(invoice));
      assertEquals(1, warnedOfANewerFormat().size(), merchant.warnings.toString());
    }

    @Test
    void olderThanAValidOneLeavesItCountedAndIsWarnedOf() throws Exception {
      newerWith(merchant.now - 90, acceptance(CAME, merchant.now - 60));
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
      assertEquals(1, warnedOfANewerFormat().size(), merchant.warnings.toString());
    }

    @Test
    void datedAfterTheLeasesEndIsSetAsideAndWarnedOf() throws Exception {
      newerWith(invoice.expiresAt() + 121, acceptance(CAME));
      assertEquals(unconfirmed(), sattleme.getStatus(invoice));
      assertEquals(1, warnedOfANewerFormat().size(), merchant.warnings.toString());
    }
  }

  @Nested
  class AMerchantsAcceptanceWithRestInvoicesTheToleranceTheLightningPart {
    @Test
    void takesWhatRestInvoicesPaidFromItOnceUnconfirmedThenPaidMixed() throws Exception {
      // 40 000 on-chain: a rest invoice of 9 000, paid; then the transaction replaced (RBF) by one
      // paying 21 000: 30 000 in all, 19 000 asked.
      merchant.chain.drop(ADDRESS, txid());
      JsonObject first = Chain.payment(Chain.txid("first"), ADDRESS, 40_000);
      merchant.chain.add(ADDRESS, first);
      sattleme.getStatus(invoice);
      TestMerchant.MadeInvoice rest = madeOf(9000).get(0);
      merchant.payInvoice(rest.paymentHash());
      merchant.now += 1;
      merchant.chain.drop(ADDRESS, Js.text(first, "txid"));
      List<String> coins = new ArrayList<>();
      for (JsonValue input : Js.list(first, "vin")) {
        coins.add(Js.text(input, "txid") + ":" + Js.integer(input, "vout"));
      }
      JsonObject bump = Chain.payment(Chain.txid("bump"), ADDRESS, 21_000, coins, null);
      String bumpTxid = Js.text(bump, "txid");
      merchant.chain.add(ADDRESS, bump);
      PaymentStatus.Open open =
          assertInstanceOf(PaymentStatus.Open.class, sattleme.getStatus(invoice));
      assertEquals("30000", open.received());
      assertEquals("19000", open.rest().sats());
      // The merchant accepts what came: 21 000 due on-chain (30 000 less the 9 000 of the rest).
      hold(acceptance(CAME));
      assertEquals(
          new PaymentStatus.Unconfirmed("30000", 0, List.of(bumpTxid)),
          sattleme.getStatus(invoice));
      assertEquals(List.of(30_000L), acceptanceRecords());
      merchant.chain.confirm(ADDRESS, bumpTxid, merchant.chain.height - 2);
      merchant.now = invoice.expiresAt() + 3600;
      assertEquals(
          new PaymentStatus.PaidMixed("30000", List.of(bumpTxid), List.of(rest.preimage())),
          sattleme.getStatus(invoice));
    }

    static Stream<Arguments> tolerances() {
      return Stream.of(
          Arguments.of("the merchant's acceptance's, lower", CAME, CAME),
          Arguments.of("the tolerance's, lower", 48_000L, 46_550L));
    }

    @ParameterizedTest(name = "with the tolerance, judges with the lower: {0}")
    @MethodSource("tolerances")
    void withTheToleranceJudgesWithTheLower(String name, long accepted, long came)
        throws Exception {
      merchant.stop();
      startWith(500, came);
      hold(acceptance(accepted));
      assertEquals(
          new PaymentStatus.Unconfirmed(Long.toString(came), 0, List.of(txid())),
          sattleme.getStatus(invoice));
      assertEquals(List.of(came), acceptanceRecords());
    }

    @Test
    void changesNothingOnceTheLightningInvoiceIsPaid() throws Exception {
      hold(acceptance(CAME));
      merchant.payInvoice(invoice.paymentHash());
      assertInstanceOf(PaymentStatus.PaidLightning.class, sattleme.getStatus(invoice));
    }

    @Test
    void isNetworkWhenFewerThanAMajorityAnswerItsRead() throws Exception {
      // A first check published the witness and asked the rest: the next one publishes nothing
      // before it reads (the relays that fail its read fail every later read on every relay too).
      assertUnderpaid(sattleme.getStatus(invoice));
      hold(acceptance(CAME));
      muteRead(1, 2);
      assertThrows(NetworkException.class, () -> sattleme.getStatus(invoice));
      // Its Lightning invoice paid: paid all the same.
      merchant.payInvoice(invoice.paymentHash());
      assertInstanceOf(PaymentStatus.PaidLightning.class, sattleme.getStatus(invoice));
    }

    @Test
    void isSeenByWaitForPaymentAtItsNextCheckOfTheOnchainPartTheRestGivenTillThen()
        throws Exception {
      RelayNet.ExplorerSocket socket = merchant.startExplorerSocket();
      List<PaymentStatus> statuses = new CopyOnWriteArrayList<>();
      // The full checks so far: each asks the explorer for the address's transactions once.
      int asked = merchant.chain.asked.size();
      BooleanSupplier twoFullChecks = () -> fullChecks(asked) == 2;
      CompletableFuture<PaymentStatus> waiting =
          sattleme.waitForPaymentAsync(
              invoice, WaitOptions.builder().intervalMs(250).onStatus(statuses::add).build());
      // The first full check, and the one the socket's connection starts, ended.
      until(() -> socket.messages.size() == 2 && twoFullChecks.getAsBoolean());
      Thread.sleep(500);
      PaymentStatus underpaid = underpaid();
      assertEquals(underpaid, statuses.get(statuses.size() - 1));
      // The merchant accepts: no message of the explorer, no check of the on-chain part; the checks
      // of the Lightning invoices give the status with its rest still.
      hold(acceptance(CAME));
      int seen = statuses.size();
      until(() -> statuses.size() > seen + 1);
      assertEquals(2, fullChecks(asked));
      for (PaymentStatus status : statuses.subList(seen, statuses.size())) {
        assertEquals(underpaid, status);
      }
      // The next check of the on-chain part (a block here; every 60 s at least) counts it.
      socket.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
      until(() -> statuses.get(statuses.size() - 1) instanceof PaymentStatus.Unconfirmed);
      assertEquals(unconfirmed(), statuses.get(statuses.size() - 1));
      merchant.chain.confirm(ADDRESS, txid(), merchant.chain.height - 2);
      socket.push(Js.obj("block", Js.obj("height", merchant.chain.height)));
      assertEquals(
          new PaymentStatus.PaidOnchain(Long.toString(CAME), List.of(txid())),
          waiting.get(20, TimeUnit.SECONDS));
      assertEquals(List.of(CAME), acceptanceRecords());
    }

    private int fullChecks(int asked) {
      List<String> all = new ArrayList<>(merchant.chain.asked);
      List<String> since = all.subList(asked, all.size());
      return (int) since.stream().filter(url -> url.endsWith("/txs")).count();
    }
  }
}
