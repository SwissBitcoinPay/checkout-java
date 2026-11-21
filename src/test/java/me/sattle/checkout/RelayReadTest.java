package me.sattle.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import me.sattle.checkout.internal.Checkout;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Derive;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.crypto.Hashes;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;
import me.sattle.checkout.internal.nostr.Quorum;
import me.sattle.checkout.internal.nostr.RelayPool;
import me.sattle.checkout.internal.onchain.Leases;
import me.sattle.checkout.support.Chain;
import me.sattle.checkout.support.FixedRandom;
import me.sattle.checkout.support.Js;
import me.sattle.checkout.support.LocalRelay;
import me.sattle.checkout.support.RelayNet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Reads of the relays (SPEC.md §13), against local relays: a read on every relay counts as answered
 * only the relays whose read ended (a relay still reading gives its pages as events, and that is
 * all); a paged read goes on from what the relays sent, every event with a string {@code id} and a
 * safe integer {@code created_at} counted, valid or not.
 */
class RelayReadTest {
  private static final long NOW = 1_791_378_000L;
  private final RelayNet net = new RelayNet();

  @AfterEach
  void stop() {
    net.close();
  }

  private RelayPool pool(long timeoutMs) {
    return new RelayPool(net, timeoutMs, new RandomBytes(new SecureRandom()), () -> NOW);
  }

  /** A record anybody writes at {@code d} (it is public): a throwaway key signs it. */
  private static NostrEvent junk(String d, long createdAt, int seed) {
    return Events.sign(
        30078,
        createdAt,
        List.of(List.of("d", d)),
        "x",
        Hashes.sha256(Text.utf8("throwaway " + seed)),
        new RandomBytes(new SecureRandom()));
  }

  private static LocalRelay.Behaviour delayed(long ms) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.queryDelayMs = ms;
    return behaviour;
  }

  /**
   * A relay that asks to authenticate (NIP-42) at each request, then never answers the AUTH: its
   * requests and AUTHs counted.
   */
  private WebSocketFactory silentAuth(String silent, AtomicInteger requests, AtomicInteger auths) {
    return (java.net.URI uri,
        Map<String, String> headers,
        java.time.Duration timeout,
        WebSocketListener listener) -> {
      if (!uri.toString().equals(silent)) return net.open(uri, headers, timeout, listener);
      me.sattle.checkout.internal.Tasks.schedule(5, listener::onOpen);
      return new WebSocketConnection() {
        @Override
        public void send(String text) {
          me.sattle.checkout.internal.json.JsonArray message =
              me.sattle.checkout.internal.json.Json.array(Js.parse(text));
          String type = me.sattle.checkout.internal.json.Json.string(message.get(0));
          if ("REQ".equals(type) || "EVENT".equals(type)) {
            requests.incrementAndGet();
            boolean read = "REQ".equals(type);
            String id =
                read
                    ? me.sattle.checkout.internal.json.Json.string(message.get(1))
                    : Js.text(message.get(1), "id");
            me.sattle.checkout.internal.Tasks.schedule(
                5,
                () -> {
                  listener.onText(Js.write(Js.arr("AUTH", "challenge-1")));
                  listener.onText(
                      Js.write(
                          read
                              ? Js.arr("CLOSED", id, "auth-required: sign in")
                              : Js.arr("OK", id, false, "auth-required: sign in")));
                });
          } else if ("AUTH".equals(type)) {
            auths.incrementAndGet();
          }
        }

        @Override
        public void close() {}
      };
    };
  }

  @Test
  void countsARelayWhoseAuthenticationRanOutOfTimeAsFailedForTheRestOfTheCall() throws Exception {
    // SPEC.md §13: the wait for the OK of an authentication is part of the request's time; a
    // request that runs out of time there ran out of time, never a refusal asked again.
    String silent = "wss://silent-auth.test";
    net.add("wss://b.test");
    net.add("wss://c.test");
    List<String> relays = List.of(silent, "wss://b.test", "wss://c.test");
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger auths = new AtomicInteger();
    RelayPool pool =
        new RelayPool(
            silentAuth(silent, requests, auths),
            1000,
            new RandomBytes(new SecureRandom()),
            () -> NOW);
    try {
      RelayPool.Answers first =
          pool.queryRelays(
                  relays, Js.obj("kinds", List.of(1)), FixedRandom.bytes(1), 2, false, v -> v, true)
              .get(10, TimeUnit.SECONDS);
      assertEquals(2, first.answered());
      assertEquals(1, requests.get());
      assertEquals(1, auths.get());
      // On every relay again: not waited for, not asked again.
      long started = System.nanoTime();
      RelayPool.Answers second =
          pool.queryRelays(
                  relays, Js.obj("kinds", List.of(2)), FixedRandom.bytes(2), 2, false, v -> v, true)
              .get(10, TimeUnit.SECONDS);
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertEquals(2, second.answered());
      assertEquals(1, requests.get());
      assertTrue(ms < 1000, ms + " ms: waited for a relay that failed earlier in the call");
    } finally {
      pool.close();
    }
    // A write too: its authentication's time out, a failure; the next read on every relay skips it.
    requests.set(0);
    auths.set(0);
    pool =
        new RelayPool(
            silentAuth(silent, requests, auths),
            1000,
            new RandomBytes(new SecureRandom()),
            () -> NOW);
    try {
      NostrEvent event = junk("ab".repeat(32), NOW, 1);
      Quorum.publish(pool, relays, event, FixedRandom.bytes(3), "not taken");
      // Past the majority, the silent relay's write ends with its time.
      Thread.sleep(1300);
      assertEquals(1, requests.get());
      assertEquals(1, auths.get());
      long started = System.nanoTime();
      RelayPool.Answers read =
          pool.queryRelays(
                  relays, Js.obj("kinds", List.of(3)), FixedRandom.bytes(4), 2, false, v -> v, true)
              .get(10, TimeUnit.SECONDS);
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertEquals(2, read.answered());
      assertEquals(1, requests.get());
      assertTrue(ms < 1000, ms + " ms: waited for a relay that failed earlier in the call");
    } finally {
      pool.close();
    }
  }

  @Test
  void authenticatesWithAnAuthMessageNeverAnEvent() throws Exception {
    // SPEC.md §13, NIP-42: ["AUTH", <event>], its event of kind 22242 dated now, naming the relay
    // and its challenge, content "", signed by the read's key; never an EVENT.
    LocalRelay.Behaviour auth = new LocalRelay.Behaviour();
    auth.requireAuth = true;
    LocalRelay relay = net.add("wss://auth.test", auth);
    net.add("wss://b.test");
    net.add("wss://c.test");
    relay.store(junk("ab".repeat(32), NOW, 1));
    byte[] key = FixedRandom.bytes(5);
    RelayPool pool = pool(2000);
    try {
      RelayPool.Answers read =
          pool.queryRelays(
                  List.of("wss://auth.test", "wss://b.test", "wss://c.test"),
                  Js.obj("kinds", List.of(30078)),
                  key,
                  3,
                  false,
                  value -> value,
                  false)
              .get(10, TimeUnit.SECONDS);
      assertEquals(3, read.answered());
      assertEquals(1, read.events().get("wss://auth.test").size());
    } finally {
      pool.close();
    }
    assertEquals(1, relay.auths.size());
    NostrEvent event = Events.checked(relay.auths.get(0));
    assertEquals(22242, event.kind());
    assertEquals(NOW, event.createdAt());
    assertEquals("", event.content());
    assertEquals(me.sattle.checkout.internal.crypto.Curve.publicKeyOf(key), event.pubkey());
    assertEquals("wss://auth.test", event.tag("relay"));
    assertEquals(2, event.tags().size());
    assertEquals(List.of(event.pubkey()), relay.authenticated);
    // Asked again once authenticated; nothing of kind 22242 sent as an event.
    assertEquals(2, relay.queries.size());
    assertEquals(List.of(), relay.received.stream().filter(e -> e.kind() == 22242).toList());
  }

  /** What a raw connection to a local relay heard. */
  private static final class Heard implements WebSocketListener {
    final java.util.concurrent.BlockingQueue<me.sattle.checkout.internal.json.JsonArray> messages =
        new java.util.concurrent.LinkedBlockingQueue<>();

    @Override
    public void onOpen() {}

    @Override
    public java.util.concurrent.CompletionStage<?> onText(String message) {
      messages.add(me.sattle.checkout.internal.json.Json.array(Js.parse(message)));
      return null;
    }

    @Override
    public void onClose(Throwable error) {}

    me.sattle.checkout.internal.json.JsonArray next() throws InterruptedException {
      me.sattle.checkout.internal.json.JsonArray message = messages.poll(5, TimeUnit.SECONDS);
      if (message == null) throw new AssertionError("nothing heard");
      return message;
    }
  }

  @Test
  void hasALocalRelayThatTakesAnAuthenticationOnlyAsNip42SendsIt() throws Exception {
    // So that a test of the package fails when it authenticates any other way.
    LocalRelay.Behaviour auth = new LocalRelay.Behaviour();
    auth.requireAuth = true;
    String url = "wss://strict.test";
    LocalRelay relay = net.add(url, auth);
    Heard heard = new Heard();
    WebSocketConnection socket =
        net.open(java.net.URI.create(url), Map.of(), java.time.Duration.ofSeconds(5), heard);
    me.sattle.checkout.internal.json.JsonArray asked = heard.next();
    assertEquals(2, asked.size());
    assertEquals("AUTH", me.sattle.checkout.internal.json.Json.string(asked.get(0)));
    String challenge = me.sattle.checkout.internal.json.Json.string(asked.get(1));
    byte[] key = FixedRandom.bytes(6);
    RandomBytes random = new RandomBytes(new SecureRandom());
    // Each way but NIP-42's: refused, nobody authenticated, nothing kept.
    List<NostrEvent> wrong =
        List.of(
            Events.sign(22242, NOW, List.of(List.of("relay", url)), "", key, random),
            Events.sign(
                22242,
                NOW,
                List.of(List.of("relay", "wss://other.test"), List.of("challenge", challenge)),
                "",
                key,
                random),
            Events.sign(
                22242,
                NOW,
                List.of(List.of("relay", url), List.of("challenge", challenge + "x")),
                "",
                key,
                random),
            Events.sign(
                22242,
                NOW,
                List.of(List.of("relay", url), List.of("challenge", challenge)),
                "hello",
                key,
                random),
            Events.sign(
                1,
                NOW,
                List.of(List.of("relay", url), List.of("challenge", challenge)),
                "",
                key,
                random));
    for (NostrEvent event : wrong) {
      socket.send(Js.write(Js.arr("AUTH", event.toJson())));
      assertEquals(Js.arr("OK", event.id(), false, "invalid: bad auth"), heard.next());
    }
    NostrEvent right =
        Events.sign(
            22242,
            NOW,
            List.of(List.of("relay", url), List.of("challenge", challenge)),
            "",
            key,
            random);
    // The right event, sent as an EVENT: refused, kept nowhere, authenticating nobody.
    socket.send(Js.write(Js.arr("EVENT", right.toJson())));
    assertEquals(
        Js.arr("OK", right.id(), false, "invalid: authenticate with an AUTH message"),
        heard.next());
    socket.send(Js.write(Js.arr("REQ", "s1", Js.obj("kinds", List.of(22242)))));
    assertEquals(Js.arr("CLOSED", "s1", "auth-required: sign in first"), heard.next());
    assertEquals(List.of(), relay.authenticated);
    assertEquals(List.of(), relay.events);
    // As an AUTH: taken.
    socket.send(Js.write(Js.arr("AUTH", right.toJson())));
    assertEquals(Js.arr("OK", right.id(), true, ""), heard.next());
    assertEquals(List.of(right.pubkey()), relay.authenticated);
    socket.send(Js.write(Js.arr("REQ", "s2", Js.obj("kinds", List.of(22242)))));
    assertEquals(Js.arr("EOSE", "s2"), heard.next());
    socket.close();
  }

  /** Random bytes that count how many times they were drawn once {@code after} is set. */
  private static final class CountingRandom extends SecureRandom {
    private static final long serialVersionUID = 1L;
    final java.util.concurrent.atomic.AtomicBoolean after =
        new java.util.concurrent.atomic.AtomicBoolean();
    final AtomicInteger drawnAfter = new AtomicInteger();

    @Override
    public synchronized void nextBytes(byte[] bytes) {
      if (after.get()) drawnAfter.incrementAndGet();
      super.nextBytes(bytes);
    }
  }

  /** A message between the package and a relay: its type, and whether the read had gone on. */
  private record Said(String relay, boolean toRelay, String type, boolean after) {}

  /** {@code net}, every message both ways recorded, with whether {@code after} was set. */
  private WebSocketFactory recorded(
      java.util.concurrent.atomic.AtomicBoolean after, List<Said> said) {
    return (uri, headers, timeout, listener) -> {
      String url = uri.toString();
      WebSocketConnection socket =
          net.open(
              uri,
              headers,
              timeout,
              new WebSocketListener() {
                @Override
                public void onOpen() {
                  listener.onOpen();
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onText(String message) {
                  said.add(new Said(url, false, typeOf(message), after.get()));
                  return listener.onText(message);
                }

                @Override
                public void onClose(Throwable error) {
                  listener.onClose(error);
                }
              });
      return new WebSocketConnection() {
        @Override
        public void send(String text) {
          said.add(new Said(url, true, typeOf(text), after.get()));
          socket.send(text);
        }

        @Override
        public void close() {
          socket.close();
        }
      };
    };
  }

  private static String typeOf(String message) {
    return me.sattle.checkout.internal.json.Json.string(
        me.sattle.checkout.internal.json.Json.array(Js.parse(message)).get(0));
  }

  @Test
  void asksNothingMoreOfTheRelaysAReadWentOnWithout() throws Exception {
    // SPEC.md §13: once a read went on, the relays it dropped end their requests and ask nothing
    // more: no page, no authentication, no query again, and no random bytes (a subscription id)
    // drawn for them. Four relays answer (empty) 100 ms late, a majority of seven. Of the three the
    // read drops then: one answers its first page 800 ms late, with events that would page on;
    // one asks for authentication at once, sent it, and is answered 800 ms late; one asks for
    // authentication 800 ms late. The connections stay open meanwhile.
    List<String> relays = new ArrayList<>();
    for (int i = 1; i <= 4; i += 1) {
      net.add("wss://answers-" + i + ".test", delayed(100));
      relays.add("wss://answers-" + i + ".test");
    }
    LocalRelay pagesLate = net.add("wss://pages-late.test", delayed(800));
    for (int i = 0; i < 3; i += 1) pagesLate.take(junk("ab".repeat(32), NOW - i, 10 + i));
    LocalRelay.Behaviour authenticatedLate = new LocalRelay.Behaviour();
    authenticatedLate.requireAuth = true;
    authenticatedLate.authDelayMs = 800;
    LocalRelay authenticatesLate = net.add("wss://authenticates-late.test", authenticatedLate);
    LocalRelay.Behaviour askedLate = delayed(800);
    askedLate.requireAuth = true;
    LocalRelay asksLate = net.add("wss://asks-late.test", askedLate);
    relays.addAll(
        List.of("wss://pages-late.test", "wss://authenticates-late.test", "wss://asks-late.test"));
    CountingRandom random = new CountingRandom();
    List<Said> said = new java.util.concurrent.CopyOnWriteArrayList<>();
    RelayPool pool =
        new RelayPool(recorded(random.after, said), 2000, new RandomBytes(random), () -> NOW);
    try {
      java.util.concurrent.CompletableFuture<RelayPool.Answers> reading =
          pool.queryRelays(
              relays,
              Js.obj("kinds", List.of(30078)),
              FixedRandom.bytes(7),
              4,
              true,
              v -> v,
              false);
      // Set where the read goes on, after it dropped the others: what is sent later is "after".
      reading.whenComplete((answers, error) -> random.after.set(true));
      RelayPool.Answers read = reading.get(10, TimeUnit.SECONDS);
      assertEquals(4, read.answered());
      assertTrue(read.outcome().answered());
      // The late answers come meanwhile: a page, the OK of an authentication, a CLOSED.
      Thread.sleep(1300);
      assertTrue(said.contains(new Said("wss://pages-late.test", false, "EOSE", true)), "" + said);
      assertTrue(
          said.contains(new Said("wss://authenticates-late.test", false, "OK", true)), "" + said);
      assertTrue(said.contains(new Said("wss://asks-late.test", false, "CLOSED", true)), "" + said);
      assertEquals(
          List.of(),
          said.stream().filter(message -> message.toRelay() && message.after()).toList());
      assertEquals(0, random.drawnAfter.get());
      assertEquals(1, pagesLate.queries.size());
      assertEquals(1, authenticatesLate.queries.size());
      assertEquals(1, authenticatesLate.auths.size());
      assertEquals(1, asksLate.queries.size());
      assertEquals(0, asksLate.auths.size());
    } finally {
      pool.close();
    }
    // The same relays read on every relay, which waits for them: each of the three does then what
    // the read above dropped it before (pages on, asks again, authenticates).
    for (LocalRelay relay : List.of(pagesLate, authenticatesLate, asksLate)) {
      relay.queries.clear();
      relay.auths.clear();
    }
    pool = new RelayPool(net, 2000, new RandomBytes(new SecureRandom()), () -> NOW);
    try {
      pool.queryRelays(
              relays, Js.obj("kinds", List.of(30078)), FixedRandom.bytes(7), 4, true, v -> v, true)
          .get(10, TimeUnit.SECONDS);
      assertTrue(pagesLate.queries.size() >= 2, pagesLate.queries.toString());
      assertEquals(2, authenticatesLate.queries.size());
      assertEquals(1, asksLate.auths.size());
      assertEquals(2, asksLate.queries.size());
    } finally {
      pool.close();
    }
  }

  @Test
  void writesNothingMoreOnceAPublicationsRequestRanOutOfTimeWaitingForItsAuthentication()
      throws Exception {
    // SPEC.md §13: the wait for the OK of an authentication is part of the request's time. Two
    // relays take the event at once; the third asks for authentication and answers it 700 ms
    // late, past its request's 300 ms: that request ran out of time, and writes nothing more.
    net.add("wss://b.test");
    net.add("wss://c.test");
    LocalRelay.Behaviour late = new LocalRelay.Behaviour();
    late.requireAuth = true;
    late.authDelayMs = 700;
    LocalRelay relay = net.add("wss://authenticates-late.test", late);
    CountingRandom random = new CountingRandom();
    List<Said> said = new java.util.concurrent.CopyOnWriteArrayList<>();
    RelayPool pool =
        new RelayPool(recorded(random.after, said), 300, new RandomBytes(random), () -> NOW);
    try {
      NostrEvent event = junk("cd".repeat(32), NOW, 2);
      pool.publishToQuorum(
              List.of("wss://authenticates-late.test", "wss://b.test", "wss://c.test"),
              event,
              FixedRandom.bytes(8),
              2)
          .get(10, TimeUnit.SECONDS);
      // Its request out of time: what the pool sends that relay after it is "after".
      Thread.sleep(450);
      random.after.set(true);
      Thread.sleep(800);
      assertTrue(
          said.contains(new Said("wss://authenticates-late.test", false, "OK", true)), "" + said);
      assertEquals(
          List.of(),
          said.stream().filter(message -> message.toRelay() && message.after()).toList());
      assertEquals(0, random.drawnAfter.get());
      assertEquals(1, relay.auths.size());
      assertEquals(List.of(event), relay.received);
      assertEquals(List.of(), relay.events);
    } finally {
      pool.close();
    }
  }

  /**
   * Publishes an event on two relays that refuse it 200 ms late and {@code late}, which asks for
   * authentication at once and answers it 700 ms late; {@code end} ends the publication (it fails
   * at the refusals, or is cancelled). Then, while that answer comes, the pool sends nothing more
   * for it, draws no random bytes, and releasing the pool closes its connections at once: nothing
   * waits for that relay.
   */
  private void endsWithItsPublication(
      java.util.function.Function<java.util.concurrent.CompletableFuture<Void>, Throwable> end)
      throws Exception {
    LocalRelay.Behaviour refuses = new LocalRelay.Behaviour();
    refuses.refuse = "blocked: not here";
    refuses.writeDelayMs = 200;
    net.add("wss://b.test", refuses);
    net.add("wss://c.test", refuses);
    LocalRelay.Behaviour authenticatedLate = new LocalRelay.Behaviour();
    authenticatedLate.requireAuth = true;
    authenticatedLate.authDelayMs = 700;
    LocalRelay late = net.add("wss://authenticates-late.test", authenticatedLate);
    CountingRandom random = new CountingRandom();
    List<Said> said = new java.util.concurrent.CopyOnWriteArrayList<>();
    RelayPool pool =
        new RelayPool(recorded(random.after, said), 2000, new RandomBytes(random), () -> NOW);
    NostrEvent event = junk("ef".repeat(32), NOW, 3);
    java.util.concurrent.CompletableFuture<Void> publishing =
        Quorum.publishAsync(
            pool,
            List.of("wss://authenticates-late.test", "wss://b.test", "wss://c.test"),
            event,
            FixedRandom.bytes(9),
            "not taken");
    // The relay asked for authentication and was sent it before the publication ended.
    for (int i = 0; i < 200 && late.auths.isEmpty(); i += 1) Thread.sleep(5);
    assertEquals(1, late.auths.size());
    Throwable error = end.apply(publishing);
    random.after.set(true);
    assertTrue(publishing.isCompletedExceptionally(), String.valueOf(error));
    try {
      Thread.sleep(1000);
      assertTrue(
          said.contains(new Said("wss://authenticates-late.test", false, "OK", true)), "" + said);
      assertEquals(
          List.of(),
          said.stream().filter(message -> message.toRelay() && message.after()).toList());
      assertEquals(0, random.drawnAfter.get());
      assertEquals(List.of(event), late.received);
      // Released: no request is left to wait for.
      long released = System.nanoTime();
      pool.release();
      for (int i = 0; i < 200 && late.openConnections() > 0; i += 1) Thread.sleep(5);
      assertEquals(0, late.openConnections());
      assertTrue(System.nanoTime() - released < 1_000_000_000L);
    } finally {
      pool.close();
    }
  }

  @Test
  void asksNothingMoreForAPublicationThatFailed() throws Exception {
    // SPEC.md §13: only a publication's relays past its majority finish their own request; one
    // that a majority can no longer take is over, its other relays asked nothing more.
    endsWithItsPublication(publishing -> {
      try {
        publishing.get(5, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        return e.getCause();
      } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
        throw new AssertionError(e);
      }
      throw new AssertionError("published");
    });
  }

  @Test
  void asksNothingMoreForAPublicationNobodyWaitsFor() throws Exception {
    // A publication whose future is cancelled (a part of the call that failed, or the call
    // cancelled): its relays are asked nothing more either.
    endsWithItsPublication(publishing -> {
      publishing.cancel(true);
      return null;
    });
  }

  /**
   * A complete read of three relays that ask for authentication and answer it 600 ms late, ended by
   * {@code end} while they authenticate; then, while their answers come, the pool sends them
   * nothing more and draws no random bytes.
   */
  private void endsWithTheCall(
      java.util.function.BiConsumer<
              RelayPool, java.util.concurrent.CompletableFuture<RelayPool.CompleteAnswers>>
          end,
      boolean released)
      throws Exception {
    List<String> urls = List.of("wss://late-1.test", "wss://late-2.test", "wss://late-3.test");
    List<LocalRelay> late = new ArrayList<>();
    for (String url : urls) {
      LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
      behaviour.requireAuth = true;
      behaviour.authDelayMs = 600;
      late.add(net.add(url, behaviour));
    }
    CountingRandom random = new CountingRandom();
    List<Said> said = new java.util.concurrent.CopyOnWriteArrayList<>();
    RelayPool pool =
        new RelayPool(recorded(random.after, said), 2000, new RandomBytes(random), () -> NOW);
    try {
      java.util.concurrent.CompletableFuture<RelayPool.CompleteAnswers> read =
          pool.queryComplete(
              urls, Js.obj("kinds", List.of(30078)), FixedRandom.bytes(7), 2, v -> v, false);
      // Every relay asked for authentication and was sent it; none answered it yet.
      for (int i = 0; i < 200 && late.stream().anyMatch(r -> r.auths.isEmpty()); i += 1) {
        Thread.sleep(5);
      }
      for (LocalRelay relay : late) assertEquals(1, relay.auths.size());
      random.after.set(true);
      end.accept(pool, read);
      // Their authentications are answered meanwhile, on connections still open; released, the
      // pool has nothing left to wait for and closes them at once.
      Thread.sleep(900);
      for (int i = 0; i < urls.size(); i += 1) {
        if (released) assertEquals(0, late.get(i).openConnections(), urls.get(i));
        else assertTrue(said.contains(new Said(urls.get(i), false, "OK", true)), "" + said);
      }
      assertEquals(
          List.of(),
          said.stream().filter(message -> message.toRelay() && message.after()).toList());
      assertEquals(0, random.drawnAfter.get());
      for (LocalRelay relay : late) assertEquals(1, relay.queries.size());
    } finally {
      pool.close();
    }
  }

  @Test
  void endsAReadWhenItsCallReleasesThePoolWhileItsRelaysAuthenticate() throws Exception {
    // SPEC.md §13, §4: a call that fails while one of its reads is under way (another part failed)
    // releases its pool: the read ends there, its relays asked nothing more once their
    // authentication ends; only a publication's relays past its majority go on.
    endsWithTheCall(
        (pool, read) -> {
          pool.release();
          try {
            assertFalse(read.get(5, TimeUnit.SECONDS).outcome().answered());
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        },
        true);
  }

  @Test
  void endsAReadNobodyWaitsForWhileItsRelaysAuthenticate() throws Exception {
    // Its future cancelled (the part of the call that read was interrupted): the read ends.
    endsWithTheCall((pool, read) -> read.cancel(true), false);
  }

  @Test
  void countsAsAnsweredOnlyTheRelaysWhoseReadEndedNeverOneStillReading() throws Exception {
    byte[] registryKey = FixedRandom.bytes(3);
    String address = Chain.depositAddress("answered");
    String d = Leases.leaseAddress(registryKey, address);
    Derive.Sale sale =
        Derive.Sale.of(
            FixedRandom.bytes(2), new Derive.Registry(registryKey, "ab".repeat(32)), "ORD-1");
    NostrEvent lease =
        Leases.leaseEvent(
            sale,
            new Leases.Terms(
                address,
                1000,
                null,
                null,
                NOW,
                NOW + 900,
                new Checkout.DeclaredPrice(1000, 0, "SAT"),
                "ORD-1",
                0,
                null),
            NOW,
            new RandomBytes(new SecureRandom()));
    // r1 fails at once (no such relay). r2's read ends, incomplete: more events of one second than
    // a page holds, 400 ms a page. r3 holds the lease and 20 pages of older records, 150 ms a page:
    // still reading when the read can no longer be complete, its first page (the lease) sent.
    LocalRelay r2 = net.add("wss://r2.test", delayed(400));
    for (int i = 0; i < 101; i += 1) r2.take(junk(d, NOW, i));
    LocalRelay r3 = net.add("wss://r3.test", delayed(150));
    r3.take(lease);
    for (int i = 0; i < 1950; i += 1) r3.take(junk(d, NOW - 10 - i, 1000 + i));
    List<String> relays = List.of("wss://r1.test", "wss://r2.test", "wss://r3.test");
    RelayPool pool = pool(5000);
    try {
      long started = System.nanoTime();
      Leases.LeaseRead read =
          Leases.readLeases(
              pool,
              registryKey,
              List.of(address),
              relays,
              NOW,
              FixedRandom.of("read"),
              true,
              message -> {});
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      // Ended once a majority could no longer read completely: r2's end, long before r3's.
      assertTrue(ms < 2500, ms + " ms");
      // r3's pages are kept as events: the lease is among them.
      assertEquals(
          List.of(lease.id()),
          read.records().get(address).stream().map(record -> record.event().id()).toList());
      // One relay of three answered (r2): no majority, whatever r3 sent.
      assertFalse(read.answered());
      assertFalse(read.complete());
    } finally {
      pool.close();
    }
  }

  @Test
  void countsAsAnsweredTheRelaysWhoseReadEndedCompleteOrNot() throws Exception {
    byte[] registryKey = FixedRandom.bytes(3);
    String address = Chain.depositAddress("answered");
    String d = Leases.leaseAddress(registryKey, address);
    LocalRelay r1 = net.add("wss://r1.test", delayed(0));
    for (int i = 0; i < 101; i += 1) r1.take(junk(d, NOW, i));
    net.add("wss://r2.test");
    List<String> relays = List.of("wss://r1.test", "wss://r2.test", "wss://r3.test");
    RelayPool pool = pool(2000);
    try {
      Leases.LeaseRead read =
          Leases.readLeases(
              pool,
              registryKey,
              List.of(address),
              relays,
              NOW,
              FixedRandom.of("read"),
              true,
              message -> {});
      // r1 incomplete, r2 complete, r3 failed: two answered, one complete.
      assertTrue(read.answered());
      assertFalse(read.complete());
    } finally {
      pool.close();
    }
  }

  @Test
  void boundsTheWaitForTheConnectionByTheTimeoutWhateverTheFactory() throws Exception {
    // A factory that resolves the relay's name on the calling thread, 3 s each (nothing bounds a
    // name's resolution): the relays' timeout holds all the same, the wait for the connection
    // included (SPEC.md §13).
    for (String url : List.of("wss://r1.test", "wss://r2.test", "wss://r3.test")) net.add(url);
    WebSocketFactory slow =
        (uri, headers, timeout, listener) -> {
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return net.open(uri, headers, timeout, listener);
        };
    RelayPool pool = new RelayPool(slow, 300, new RandomBytes(new SecureRandom()), () -> NOW);
    try {
      long started = System.nanoTime();
      RelayPool.Answers read =
          Quorum.query(
              pool,
              List.of("wss://r1.test", "wss://r2.test", "wss://r3.test"),
              Js.obj("kinds", List.of(30078)),
              new byte[32],
              false,
              value -> value,
              false);
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(ms < 1500, ms + " ms");
      assertEquals(0, read.answered());
    } finally {
      pool.close();
    }
  }

  private static final JsonValue READS = Js.spec("vectors/relay-reads.json");

  /** A vector's events, each {@code [id, created_at]}, as a relay sends them. */
  private static List<JsonValue> eventsOf(JsonValue page) {
    List<JsonValue> events = new ArrayList<>();
    for (JsonValue pair : Js.items(page)) {
      List<JsonValue> items = Js.items(pair);
      events.add(new JsonObject().put("id", items.get(0)).put("created_at", items.get(1)));
    }
    return events;
  }

  /**
   * relay-reads.json's paged reads, read by RelayPool itself from a relay that answers each page as
   * the vector says ("fails": CLOSED): the {@code until} of each page it asked, whether the relay
   * answered, what the read kept.
   */
  @TestFactory
  Stream<DynamicTest> readsThePagedVectorsFromARelay() {
    return Js.list(READS, "paged").stream()
        .map(c ->
            dynamicTest(
                Js.text(c, "name"),
                () -> {
                  List<JsonValue> pages = Js.list(c, "pages");
                  AtomicInteger asked = new AtomicInteger();
                  LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
                  behaviour.script =
                      filter -> {
                        int page = asked.getAndIncrement();
                        // Past the vector's pages: a read that asks one more than it should.
                        if (page >= pages.size()) return List.of();
                        JsonValue answer = pages.get(page);
                        return answer instanceof JsonString ? null : eventsOf(answer);
                      };
                  String url = "wss://paged.test";
                  LocalRelay relay = net.add(url, behaviour);
                  RelayPool pool = pool(5000);
                  try {
                    RelayPool.Answers read =
                        pool.queryRelays(
                                List.of(url),
                                Js.obj("kinds", List.of(30078)),
                                new byte[32],
                                1,
                                true,
                                value -> value,
                                false)
                            .get(30, TimeUnit.SECONDS);
                    List<Object> untils = new ArrayList<>();
                    for (JsonObject filter : relay.queries) {
                      untils.add(filter.has("until") ? Js.integer(filter, "until") : null);
                    }
                    assertEquals(
                        Js.get(c, "expected"),
                        Js.obj(
                            "untils",
                            untils,
                            "answered",
                            read.answered() == 1,
                            "kept",
                            read.events().getOrDefault(url, List.of()).size()));
                  } finally {
                    pool.close();
                    net.relays.remove(url);
                  }
                }));
  }

  /*
   * relay-reads.json end to end (PORTING.md, steps 4 and 5): its `lists` and `onEveryRelay` cases,
   * each relay's read made by a local relay and told apart by RelayPool itself, through
   * Leases.readLeases, the client's read of the leases of deposit addresses: a complete read (step 3
   * of "Leasing an address"), and on every relay (a lease's confirmation). OnchainVectorsTest runs
   * the same cases through RelayPool.readOutcome, the function the pool decides them with. As
   * checkout-node's relay-reads tests.
   *
   * What each relay of a case does, by its state:
   * - complete: holds a few events at the lease's address (one page, not full); or 250, at distinct
   *   seconds (it pages: 100, 100, then 50); or caps its answers at 40 events (a page that is not
   *   full: complete, whatever it leaves out; SPEC.md §13 relies on relays that honor the limit).
   * - incomplete: holds 150 events of one second (a full page, then the same page again: no event
   *   it had not sent); or 2 100 at distinct seconds (a 20th page that is full); or never answers
   *   its second page (that page runs out of time).
   * - failed: refuses connections (nothing listens); or accepts them and never answers (mute: its
   *   first page runs out of time).
   * - reading: a relay still reading when the read goes on: one that holds 2 100 events at distinct
   *   seconds and answers each page a quarter of the timeout late (its read would last five
   *   timeouts); or a mute one, when the read goes on before its first page runs out of time.
   */

  /**
   * relayTimeoutMs of these reads: each page, the connection included. Ample for a local relay on a
   * busy machine: a relay that reads completely, or ends its read, never runs out of time here.
   */
  private static final long TIMEOUT_MS = 2000;

  /** How late a relay that is still reading answers each of its pages. */
  private static final long SLOW_PAGE_MS = TIMEOUT_MS / 4;

  /**
   * How late the relays that read completely answer each page, when the read goes on as soon as a
   * majority read completely and every other relay's read must have ended (or failed) by then.
   */
  private static final long LATE_COMPLETE_MS = 400;

  private static final byte[] REGISTRY_KEY = FixedRandom.bytes(9);
  private static final String ADDRESS = Chain.depositAddress("reads");
  private static final String D = Leases.leaseAddress(REGISTRY_KEY, ADDRESS);

  private int serial;

  /**
   * {@code count} events at the lease's address, anybody's (none opens as a lease: a read pages
   * through them all the same), the newest at NOW; of one second each, or all of NOW.
   */
  private List<JsonValue> events(int count, boolean oneSecond) {
    List<JsonValue> events = new ArrayList<>();
    for (int i = 0; i < count; i += 1) {
      String hex = Integer.toHexString(serial++);
      events.add(
          Js.obj(
              "id",
              "0".repeat(64 - hex.length()) + hex,
              "pubkey",
              "ab".repeat(32),
              "created_at",
              oneSecond ? NOW : NOW - i,
              "kind",
              30078,
              "tags",
              List.of(List.of("d", D)),
              "content",
              "",
              "sig",
              "00".repeat(64)));
    }
    return events;
  }

  /** The local relays that read as each state does. */
  private enum Maker {
    FEW,
    PAGES,
    CAPS,
    ONE_SECOND,
    TWENTY_PAGES,
    STALLS_AT_PAGE_2,
    REFUSES_CONNECTIONS,
    MUTE,
    SLOW_PAGER
  }

  /** A maker, and how late a relay that reads completely answers each page. */
  private record Made(Maker maker, long delayMs) {
    Made(Maker maker) {
      this(maker, 0);
    }
  }

  /** Makes the relay of {@code made} at {@code url} (none for one that refuses connections). */
  private LocalRelay make(String url, Made made) {
    LocalRelay.Behaviour behaviour = new LocalRelay.Behaviour();
    behaviour.queryDelayMs = made.delayMs();
    List<JsonValue> held =
        switch (made.maker()) {
          case FEW -> events(3, false);
          case PAGES, CAPS, STALLS_AT_PAGE_2 -> events(250, false);
          case ONE_SECOND -> events(150, true);
          case TWENTY_PAGES, SLOW_PAGER -> events(2100, false);
          case REFUSES_CONNECTIONS, MUTE -> List.of();
        };
    switch (made.maker()) {
      case REFUSES_CONNECTIONS -> {
        return null;
      }
      case CAPS -> behaviour.cap = 40;
      case STALLS_AT_PAGE_2 -> behaviour.muteQuery = filter -> filter.has("until");
      case MUTE -> behaviour.mute = true;
      case SLOW_PAGER -> behaviour.queryDelayMs = SLOW_PAGE_MS;
      default -> {}
    }
    LocalRelay relay = net.add(url, behaviour);
    for (JsonValue event : held) relay.store(event);
    return relay;
  }

  /** What one read gave: its outcome, how long it took, and the relays, by URL. */
  private record Read(
      RelayPool.Outcome outcome,
      long elapsedMs,
      Map<String, LocalRelay> relays,
      List<String> urls) {}

  /** Makes one local relay per maker, and reads the leases of ADDRESS on them. */
  private Read read(List<Made> makers, boolean everyRelay) throws InterruptedException {
    List<String> urls = new ArrayList<>();
    Map<String, LocalRelay> relays = new java.util.HashMap<>();
    for (int i = 0; i < makers.size(); i += 1) {
      String url =
          "wss://"
              + makers.get(i).maker().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
              + "-"
              + i
              + ".test";
      urls.add(url);
      relays.put(url, make(url, makers.get(i)));
    }
    RelayPool pool = pool(TIMEOUT_MS);
    try {
      long started = System.nanoTime();
      Leases.LeaseRead leases =
          Leases.readLeases(
              pool,
              REGISTRY_KEY,
              List.of(ADDRESS),
              urls,
              NOW,
              new RandomBytes(new SecureRandom()),
              everyRelay,
              message -> {});
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      return new Read(
          new RelayPool.Outcome(leases.answered(), leases.complete()), elapsedMs, relays, urls);
    } finally {
      pool.close();
      for (String url : urls) net.relays.remove(url);
    }
  }

  /** How many relays each choice of makers made so far: its makers take turns. */
  private final Map<List<Maker>, Integer> turns = new java.util.HashMap<>();

  /** Every maker the cases used. */
  private final java.util.Set<Maker> used = java.util.EnumSet.noneOf(Maker.class);

  /** The next of {@code makers}, in turn: the relays of one state vary from case to case. */
  private Maker next(Maker... makers) {
    List<Maker> key = List.of(makers);
    int turn = turns.merge(key, 1, Integer::sum) - 1;
    Maker maker = makers[turn % makers.length];
    used.add(maker);
    return maker;
  }

  /**
   * When RelayPool goes on with a complete read on every relay while these are its relays' states
   * (SPEC.md §13): once no relay is still reading ("settled"); or, with some still reading, a
   * timeout after a majority read completely ("grace"), or as soon as a majority can no longer read
   * completely ("at once"). Otherwise (null) it never does: it waits for the relays still reading,
   * until each ends or fails.
   */
  private static String goesOnWith(List<String> states) {
    int needed = RelayPool.majority(states.size());
    long reading = states.stream().filter("reading"::equals).count();
    long complete = states.stream().filter("complete"::equals).count();
    if (reading == 0) return "settled";
    if (complete >= needed) return "grace";
    if (complete + reading < needed) return "at once";
    return null;
  }

  /**
   * relay-reads.json's lists, a complete read of a list; its reads on every relay, a lease's
   * confirmation; then whether the cases used every kind of relay.
   */
  @TestFactory
  Stream<DynamicTest> readsTheListsAndTheReadsOnEveryRelayOfTheVectorsEndToEnd() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonValue c : Js.list(READS, "lists")) {
      tests.add(
          dynamicTest(
              "a complete read of a list: " + Js.text(c, "name"),
              () -> {
                boolean expected = Js.bool(c, "expected");
                // Complete: the read goes on as soon as a majority read completely, the others
                // dropped; so that each of those has ended as the case says by then, they end at
                // once and the relays that read completely answer late. Not complete: the read
                // waits for every relay.
                List<Made> makers = new ArrayList<>();
                for (String state : Js.strings(c, "relays")) {
                  makers.add(
                      switch (state) {
                        case "complete" -> {
                          Maker maker = next(Maker.FEW, Maker.PAGES, Maker.CAPS);
                          yield expected ? new Made(maker, LATE_COMPLETE_MS) : new Made(maker);
                        }
                        case "incomplete" ->
                            new Made(
                                expected
                                    ? next(Maker.ONE_SECOND)
                                    : next(
                                        Maker.ONE_SECOND,
                                        Maker.STALLS_AT_PAGE_2,
                                        Maker.TWENTY_PAGES));
                        default ->
                            new Made(
                                expected
                                    ? next(Maker.REFUSES_CONNECTIONS)
                                    : next(Maker.MUTE, Maker.REFUSES_CONNECTIONS));
                      });
                }
                assertEquals(expected, read(makers, false).outcome().complete());
              }));
    }
    List<JsonValue> producible = new ArrayList<>();
    List<JsonValue> waited = new ArrayList<>();
    for (JsonValue c : Js.list(READS, "onEveryRelay")) {
      (goesOnWith(Js.strings(c, "relays")) != null ? producible : waited).add(c);
    }
    tests.add(
        dynamicTest(
            "the reads on every relay have cases of every kind",
            () -> {
              java.util.Set<String> kinds = new java.util.HashSet<>();
              for (JsonValue c : producible) kinds.add(goesOnWith(Js.strings(c, "relays")));
              assertEquals(java.util.Set.of("at once", "grace", "settled"), kinds);
              assertFalse(waited.isEmpty());
            }));
    for (JsonValue c : producible) {
      tests.add(
          dynamicTest(
              "on every relay: " + Js.text(c, "name"),
              () -> {
                List<String> states = Js.strings(c, "relays");
                String when = goesOnWith(states);
                // A relay that is not still reading must have ended or failed when the read goes
                // on: before the timeout after a majority read completely ("grace"), or at once.
                List<Made> makers = new ArrayList<>();
                for (String state : states) {
                  makers.add(
                      new Made(
                          switch (state) {
                            case "complete" -> next(Maker.FEW, Maker.PAGES, Maker.CAPS);
                            case "incomplete" ->
                                when.equals("settled")
                                    ? next(
                                        Maker.ONE_SECOND,
                                        Maker.STALLS_AT_PAGE_2,
                                        Maker.TWENTY_PAGES)
                                    : next(Maker.ONE_SECOND, Maker.TWENTY_PAGES);
                            case "failed" ->
                                when.equals("settled")
                                    ? next(Maker.REFUSES_CONNECTIONS, Maker.MUTE)
                                    : next(Maker.REFUSES_CONNECTIONS);
                            default ->
                                when.equals("grace") ? next(Maker.SLOW_PAGER) : next(Maker.MUTE);
                          }));
                }
                Read read = read(makers, true);
                assertEquals(
                    Js.get(c, "expected"),
                    Js.obj(
                        "answered",
                        read.outcome().answered(),
                        "complete",
                        read.outcome().complete()));
                if (when.equals("grace")) {
                  // The relays still reading were waited for a timeout after the majority, not
                  // until they end.
                  assertTrue(read.elapsedMs() >= TIMEOUT_MS - 50, read.elapsedMs() + " ms");
                  for (int i = 0; i < states.size(); i += 1) {
                    if (!states.get(i).equals("reading")) continue;
                    assertTrue(
                        read.relays().get(read.urls().get(i)).queries.size() < Constants.MAX_PAGES);
                  }
                }
                if (when.equals("at once")) {
                  // Before a mute relay's first page could run out of time: it was still reading.
                  assertTrue(read.elapsedMs() < TIMEOUT_MS - 100, read.elapsedMs() + " ms");
                }
              }));
    }
    /*
     * The cases RelayPool never goes on with: fewer than a majority read completely while a
     * majority still can, some relays still reading. A complete read on every relay then waits for
     * them (SPEC.md §13: it goes on once every relay did or failed, a timeout after a majority did,
     * or as soon as a majority can no longer read completely), so no local relay can make a relay
     * still reading when it goes on: a relay that reads slowly is waited for until it ends, a mute
     * one until its first page runs out of time. With mute relays in their place, the read goes on
     * once they failed, and gives what the case expects: a relay still reading answered no more
     * than one that failed.
     */
    for (JsonValue c : waited) {
      tests.add(
          dynamicTest(
              "on every relay: "
                  + Js.text(c, "name")
                  + ": waited for until the relays reading fail",
              () -> {
                List<Made> makers = new ArrayList<>();
                for (String state : Js.strings(c, "relays")) {
                  makers.add(
                      new Made(
                          switch (state) {
                            case "complete" -> next(Maker.FEW, Maker.PAGES, Maker.CAPS);
                            case "incomplete" -> next(Maker.ONE_SECOND, Maker.TWENTY_PAGES);
                            case "failed" -> next(Maker.REFUSES_CONNECTIONS);
                            default -> next(Maker.MUTE);
                          }));
                }
                Read read = read(makers, true);
                assertTrue(read.elapsedMs() >= TIMEOUT_MS - 50, read.elapsedMs() + " ms");
                assertEquals(
                    Js.get(c, "expected"),
                    Js.obj(
                        "answered",
                        read.outcome().answered(),
                        "complete",
                        read.outcome().complete()));
              }));
    }
    tests.add(
        dynamicTest(
            "the local relays of these reads were of every kind",
            () -> assertEquals(java.util.EnumSet.allOf(Maker.class), used)));
    return tests.stream();
  }

  @Test
  void goesOnWithAPagedReadFromEveryEventWithAStringIdAndASafeIntegerDateValidOrNot()
      throws Exception {
    LocalRelay relay = net.add("wss://relay.test");
    byte[] secret = FixedRandom.bytes(9);
    // A valid event of 100, and a copy of an event of 50 whose signature does not check out: the
    // next page is asked until 50, the oldest date the relay sent, valid or not.
    NostrEvent valid =
        Events.sign(
            30078,
            100,
            List.of(List.of("d", "x")),
            "v",
            secret,
            new RandomBytes(new SecureRandom()));
    JsonObject broken =
        Js.with(
            Events.sign(
                    30078,
                    50,
                    List.of(List.of("d", "y")),
                    "w",
                    secret,
                    new RandomBytes(new SecureRandom()))
                .toJson(),
            "sig",
            "00".repeat(64));
    relay.store(valid);
    relay.store(broken);
    RelayPool pool = pool(2000);
    try {
      Map<String, List<Object>> read =
          pool.queryRelays(
                  List.of("wss://relay.test"),
                  Js.obj("kinds", List.of(30078)),
                  new byte[32],
                  1,
                  true,
                  value -> Events.checked(value) != null ? value : null,
                  false)
              .get(10, TimeUnit.SECONDS)
              .events();
      assertEquals(1, read.get("wss://relay.test").size());
      List<JsonValue> untils = new ArrayList<>();
      for (JsonObject filter : relay.queries) untils.add(filter.get("until"));
      // The first page, the next until 50, then until 49 (nothing new in that one).
      assertEquals(
          List.of(Js.of(null), Js.of(50), Js.of(49)),
          untils.stream().map(until -> until == null ? Js.of(null) : until).toList());
    } finally {
      pool.close();
    }
  }
}
