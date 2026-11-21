package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import me.sattle.checkout.internal.Channels;
import me.sattle.checkout.internal.IntegrationKey;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Warn;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.RelayNet;
import me.sattle.checkout.support.TestMerchant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Reading the integration's channel (SPEC.md §14), as checkout-node's channel tests. */
class ChannelTest {
  private static final JsonValue APP = Js.spec("vectors/app/app.json");
  private static final Warn QUIET = message -> {};

  private TestMerchant merchant;
  private RelayNet net;

  @AfterEach
  void stop() {
    if (merchant != null) merchant.stop();
    if (net != null) net.close();
  }

  /** readChannel over a pool of its own, closed once it is done. */
  private static Channels.ChannelRead readOn(
      IntegrationKey access, RelayNet sockets, long timeoutMs, Warn warn)
      throws InterruptedException {
    RandomBytes random = new RandomBytes(new SecureRandom());
    RelayPool pool =
        new RelayPool(sockets, timeoutMs, random, () -> System.currentTimeMillis() / 1000);
    try {
      return Channels.readChannel(pool, access, random, warn);
    } finally {
      pool.close();
    }
  }

  private Channels.ChannelRead read(long timeoutMs, Warn warn) throws InterruptedException {
    return readOn(IntegrationKey.parse(merchant.key()), merchant.net, timeoutMs, warn);
  }

  private Channels.ChannelRead read() throws InterruptedException {
    return read(2000, QUIET);
  }

  /** The merchant's key, with other relays. */
  private IntegrationKey keyOn(List<String> relays) {
    return IntegrationKey.parse(
        TestMerchant.formatKey(merchant.team, merchant.integrationKey, relays));
  }

  private static JsonObject removal(long updatedAt) {
    return Js.obj("version", 1, "status", "removed", "updatedAt", updatedAt);
  }

  @Test
  void readsTheChannelOfTheAppsVectorsWithItsKeyAlone() throws Exception {
    net = new RelayNet();
    LocalRelay relay = net.add("wss://relay.example");
    relay.store(Js.get(Js.get(APP, "channel"), "event"));
    Channels.ChannelRead read =
        readOn(IntegrationKey.parse(Js.text(APP, "secretKey")), net, 2000, QUIET);
    Channels.Channel channel = read.channel();
    assertEquals(false, channel.removed());
    assertEquals("Sunrise Bakery", channel.businessName());
    assertEquals("sattleme02c6047f94@breez.tips", channel.lightningAddress());
    assertEquals(
        Js.text(Js.get(Js.get(Js.get(APP, "channel"), "value"), "v"), "registryKey"),
        channel.registryKey());
    assertEquals(List.of("wss://relay.example"), read.relays());
    assertEquals(
        List.of(
            Js.obj(
                "kinds",
                List.of(30078),
                "authors",
                List.of(Js.text(Js.get(APP, "parsed"), "team")),
                "#d",
                List.of(Js.text(Js.get(APP, "derived"), "channelAddress")))),
        relay.queries);
  }

  @Test
  void keepsTheNewestCopyAndIgnoresOneSignedByAnotherKey() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    merchant.publishChannel(
        merchant.channelValue(
            200,
            Map.of(
                "profile",
                Js.obj("businessName", "Old", "lightningAddress", "a@b.test", "currency", "CHF"))),
        200,
        // On a majority, as the app publishes it: any majority that answers holds it.
        List.of("wss://relay-a.test", "wss://relay-b.test"),
        merchant.teamSecret);
    // Newer, but by another author: whoever saw the address could publish there.
    merchant.publishChannel(
        merchant.channelValue(300, Map.of("status", "removed")),
        300,
        merchant.keyRelays,
        FixedRandom.bytes(66));
    Channels.Channel channel = read().channel();
    assertEquals("Old", channel.businessName());
    assertEquals(200, channel.updatedAt());
  }

  @Test
  void ignoresACopyWhoseUpdatedAtIsNotItsDateOrThatIsDamaged() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    merchant.publishChannel(merchant.channelValue(150), 200);
    LocalRelay relay = merchant.net.get("wss://relay-b.test");
    NostrEvent good = merchant.channelEvent(merchant.channelValue(300), 300, merchant.teamSecret);
    relay.store(Js.with(good.toJson(), "content", good.content().replaceFirst("^A", "B")));
    relay.store(Js.with(good.toJson(), "sig", "00".repeat(64)));
    assertEquals(100, read().channel().updatedAt());
  }

  @Test
  void ignoresAChannelWhoseLightningAddressIsOnAnIpOrUnderLocalhostOrWhoseNameHoldsALoneSurrogate()
      throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    JsonValue profile = merchant.channelValue(0).get("profile");
    Object[][] changes = {
      {200L, "lightningAddress", "shop@10.0.0.1"},
      {201L, "lightningAddress", "shop@pay.localhost"},
      {202L, "businessName", "Sunrise \ud800"}
    };
    for (Object[] change : changes) {
      long at = (Long) change[0];
      merchant.publishChannel(
          Js.with(merchant.channelValue(at), "profile", Js.with(profile, change[1], change[2])),
          at);
    }
    assertEquals(100, read().channel().updatedAt());
  }

  @Test
  void throwsRevokedWhenTheIntegrationWasRemoved() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    merchant.publishChannel(removal(200), 200);
    RevokedException error = assertThrows(RevokedException.class, this::read);
    assertEquals("revoked", error.getCode());
  }

  @Test
  void warnsOfANewerFormatAndIgnoresIt() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    merchant.publishChannel(Js.with(merchant.channelValue(200), "version", 2), 200);
    List<String> warnings = new CopyOnWriteArrayList<>();
    assertEquals(100, read(2000, warnings::add).channel().updatedAt());
    assertEquals(1, warnings.size(), warnings.toString());
  }

  @Test
  void followsTheAccountsRelaysWhereTheChannelMovedThreeTimesAtMost() throws Exception {
    merchant = new TestMerchant();
    List<List<String>> lists =
        List.of(
            merchant.keyRelays,
            List.of("wss://second.test"),
            List.of("wss://third.test", "wss://third-b.test"),
            List.of("wss://fourth.test"),
            List.of("wss://fifth.test"));
    List<String> others = new ArrayList<>();
    for (List<String> list : lists.subList(1, lists.size())) others.addAll(list);
    merchant.start(others.toArray(String[]::new));
    for (int i = 0; i < lists.size(); i += 1) {
      List<String> next = i + 1 < lists.size() ? lists.get(i + 1) : lists.get(i);
      merchant.publishChannel(
          merchant.channelValue(
              100 + i,
              Map.of(
                  "relays",
                  next,
                  "profile",
                  Js.obj(
                      "businessName",
                      "List " + i,
                      "lightningAddress",
                      "sattleme02c6047f94@breez.tips",
                      "currency",
                      "CHF"))),
          100 + i,
          lists.get(i),
          merchant.teamSecret);
    }
    Channels.ChannelRead read = read();
    assertEquals("List 3", read.channel().businessName());
    assertEquals(List.of("wss://fourth.test"), read.relays());
    assertNull(merchant.net.connections.get("wss://fifth.test"));
  }

  @Test
  void seesARemovalOnTheListTheChannelMovedTo() {
    merchant = new TestMerchant().start("wss://new.test");
    merchant.publishChannel(
        merchant.channelValue(100, Map.of("relays", List.of("wss://new.test"))), 100);
    merchant.publishChannel(removal(200), 200, List.of("wss://new.test"), merchant.teamSecret);
    assertThrows(RevokedException.class, this::read);
  }

  @Test
  void throwsNetworkWhenTheListTheChannelMovedToDoesNotAnswer() {
    merchant = new TestMerchant().start();
    merchant.publishChannel(
        merchant.channelValue(100, Map.of("relays", List.of("wss://gone.test"))), 100);
    NetworkException error = assertThrows(NetworkException.class, this::read);
    assertTrue(error.isRetryable());
  }

  @Test
  void throwsChannelNotFoundWhenTheRelaysAnswerWithoutIt() {
    merchant = new TestMerchant().start();
    ChannelNotFoundException error = assertThrows(ChannelNotFoundException.class, this::read);
    assertEquals("channel_not_found", error.getCode());
  }

  @Test
  void goesOnOnceAMajorityAnsweredPastASilentRelayAndOneAskingToAuthenticate() throws Exception {
    LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
    mute.mute = true;
    LocalRelay.Behaviour auth = new LocalRelay.Behaviour();
    auth.requireAuth = true;
    merchant =
        new TestMerchant()
            .start(List.of(), Map.of("wss://relay-a.test", mute, "wss://relay-b.test", auth));
    merchant.publishChannel(
        merchant.channelValue(100), 100, List.of("wss://relay-b.test"), merchant.teamSecret);
    long started = System.nanoTime();
    // relay-b and relay-c are a majority: the silent relay's 5 s are not waited for.
    Channels.ChannelRead read = read(5000, QUIET);
    assertTrue(System.nanoTime() - started < 1_000_000_000L);
    assertEquals(100, read.channel().updatedAt());
    // A key of this read alone: never the team's nor a sale's.
    List<String> authenticated = merchant.net.get("wss://relay-b.test").authenticated;
    assertEquals(1, authenticated.size());
    assertNotEquals(merchant.team, authenticated.get(0));
  }

  @Test
  void closesARelayThatNeverCompletesTheHandshakeWithoutAnUncaughtError() throws Exception {
    merchant = new TestMerchant().start();
    merchant.net.blackholes.add("wss://hole.test");
    merchant.publishChannel(
        merchant.channelValue(100), 100, List.of("wss://relay-b.test"), merchant.teamSecret);
    List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
    try {
      // Read on the two others, a majority of three; the hole's connection closed meanwhile.
      Channels.ChannelRead read =
          readOn(
              keyOn(List.of("wss://hole.test", "wss://relay-b.test", "wss://relay-c.test")),
              merchant.net,
              300,
              QUIET);
      assertEquals(100, read.channel().updatedAt());
      // One of two is no majority: no answer is no absence.
      assertThrows(
          NetworkException.class,
          () ->
              readOn(
                  keyOn(List.of("wss://hole.test", "wss://relay-b.test")),
                  merchant.net,
                  300,
                  QUIET));
      Thread.sleep(400);
      assertEquals(List.of(), uncaught);
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(before);
    }
  }

  @Test
  void throwsNetworkNotAnAbsenceWhenNoRelayAnswers() {
    LocalRelay.Behaviour mute = new LocalRelay.Behaviour();
    mute.mute = true;
    merchant = new TestMerchant().start(List.of(), Map.of("wss://relay-a.test", mute));
    merchant.net.remove("wss://relay-b.test");
    merchant.net.remove("wss://relay-c.test");
    long started = System.nanoTime();
    assertThrows(NetworkException.class, () -> read(300, QUIET));
    assertTrue(System.nanoTime() - started < 2_000_000_000L);
  }

  @Test
  void readsTheMerchantAtEveryCallAsJsonAndKeepsNothingOpen() throws Exception {
    merchant = new TestMerchant().start();
    merchant.publishChannel(merchant.channelValue(100), 100);
    SattlemeClient sattleme =
        SattlemeClient.builder()
            .key(merchant.key())
            .httpClient(merchant.http)
            .webSocketFactory(merchant.net)
            .logger(new TestMerchant.ListLogger(merchant.warnings))
            .build();
    Merchant found = sattleme.merchant();
    // No lot of addresses yet: no on-chain payment.
    assertEquals(new Merchant("Sunrise Bakery", "1", "100000000", 0), found);
    assertEquals(
        Js.obj(
            "businessName",
            "Sunrise Bakery",
            "minSats",
            "1",
            "maxSats",
            "100000000",
            "onchainAddresses",
            0),
        Js.parse(found.toJson()));
    // Nothing kept: a removal stops the very next call.
    merchant.publishChannel(removal(200), 200);
    assertThrows(RevokedException.class, sattleme::merchant);
    assertEquals(2, merchant.net.connections.get("wss://relay-a.test"));
    for (String url : merchant.keyRelays) {
      LocalRelay relay = merchant.net.get(url);
      long deadline = System.nanoTime() + 2_000_000_000L;
      while (relay.openConnections() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(0, relay.openConnections(), url);
    }
  }
}
