package me.sattle.checkout.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import me.sattle.checkout.internal.SerialExecutor;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonNumber;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonString;
import me.sattle.checkout.internal.json.JsonValue;
import me.sattle.checkout.internal.nostr.Events;
import me.sattle.checkout.internal.nostr.NostrEvent;

/**
 * A Nostr relay for tests (NIP-01, NIP-42), in memory. It keeps the newest event of each address,
 * as strfry does, and can refuse writes, ask for authentication, cap its answers or never answer at
 * all. An authentication is taken only as NIP-42 has it: an {@code AUTH} message whose event of
 * kind 22242 checks out, names this relay and the connection's challenge, its content empty; one
 * sent in an {@code EVENT} is refused, and kept nowhere.
 */
public final class LocalRelay {
  /** What a relay does besides answering. */
  public static final class Behaviour {
    /** Refuses every event with this reason. */
    public String refuse;

    /** Refuses the events it gives a reason for. */
    public Function<NostrEvent, String> refuseEvent;

    /** Accepts connections and never sends anything. */
    public boolean mute;

    /** Asks every connection to authenticate (NIP-42) before reading or writing. */
    public boolean requireAuth;

    /** Sends at most this many events per query, the newest first. */
    public Integer cap;

    /** Never answers the queries it gives true for (no event, no EOSE). */
    public Predicate<JsonObject> muteQuery;

    /**
     * Answers each query this many milliseconds late, its refusal before an authentication included
     * (writes at once).
     */
    public long queryDelayMs;

    /** Answers each authentication this many milliseconds late (it is taken at once). */
    public long authDelayMs;

    /** Answers each write this many milliseconds late (it is taken, or refused, at once). */
    public long writeDelayMs;

    /** Answers a filter without {@code limit} oldest first, as nostr-rs-relay does. */
    public boolean oldestFirstWithoutLimit;

    /** Leaves out of its answers the events it gives true for: a hostile relay. */
    public Predicate<JsonValue> hide;

    /** Hears each event the relay took, once it keeps it. */
    public BiConsumer<NostrEvent, LocalRelay> onEvent;

    /**
     * Answers each query with the page it gives for its filter, whatever the relay holds: its
     * events as they are, or null for a page that fails ({@code CLOSED}).
     */
    public Function<JsonObject, List<JsonValue>> script;

    /** A relay that does nothing special. */
    public Behaviour() {}
  }

  /** What the relay keeps, valid or not (a relay is never trusted). */
  public final List<JsonValue> events = new CopyOnWriteArrayList<>();

  /** Every valid event a client sent, accepted or not. */
  public final List<NostrEvent> received = new CopyOnWriteArrayList<>();

  /** Every filter a client asked. */
  public final List<JsonObject> queries = new CopyOnWriteArrayList<>();

  /** The public keys that authenticated. */
  public final List<String> authenticated = new CopyOnWriteArrayList<>();

  /** What each {@code AUTH} message a client sent held, taken or not. */
  public final List<JsonValue> auths = new CopyOnWriteArrayList<>();

  /** Subscriptions a client closed. */
  public final List<String> closedSubscriptions = new CopyOnWriteArrayList<>();

  /** The relay's behaviour; may change while a test runs. */
  public volatile Behaviour behaviour;

  /** Its URL: what an authentication's {@code relay} tag names. */
  public final String url;

  /** Where its state lives. */
  private final SerialExecutor loop = new SerialExecutor();

  /** The kind of NIP-42's authentication event. */
  private static final int AUTH_KIND = 22242;

  private final List<MemorySocket> sockets = new CopyOnWriteArrayList<>();

  /**
   * A relay.
   *
   * @param url its URL
   * @param behaviour what it does
   */
  public LocalRelay(String url, Behaviour behaviour) {
    this.url = url;
    this.behaviour = behaviour;
  }

  /**
   * Stores {@code value} as it is (an invalid event too).
   *
   * @param value what to store
   */
  public void store(JsonValue value) {
    events.add(value);
  }

  /**
   * Stores an event as it is.
   *
   * @param event the event
   */
  public void store(NostrEvent event) {
    store(event.toJson());
  }

  /**
   * Takes {@code event} as a client's write: the newest of its address is kept.
   *
   * @param event the event
   */
  public void take(NostrEvent event) {
    synchronized (this) {
      keep(event);
    }
  }

  /**
   * The connections the package has not closed.
   *
   * @return their count
   */
  public int openConnections() {
    int open = 0;
    for (MemorySocket socket : sockets) if (!socket.isClosed()) open += 1;
    return open;
  }

  /** Ends every connection: the relay stopped. */
  void terminateAll() {
    for (MemorySocket socket : sockets) socket.terminate(new IOException("relay stopped"));
  }

  /** A connection of the package. */
  void accept(MemorySocket socket) {
    sockets.add(socket);
    Session session = new Session(socket);
    socket.serve(text -> loop.execute(() -> session.receive(text)), () -> sockets.remove(socket));
    socket.opened();
    loop.execute(session::start);
  }

  private final class Session {
    private final MemorySocket socket;
    private final String challenge = UUID.randomUUID().toString();

    /**
     * The keys this connection authenticated as (NIP-42): every one of them, as relays keep them
     * (strfry, nostr-rs-relay), not the last alone. A call shares one connection per relay between
     * writes of several authors (a checkout link, then a declaration): with the last key alone, a
     * write past the majority that authenticated first would be refused once another one did.
     */
    private final Set<String> authed = ConcurrentHashMap.newKeySet();

    Session(MemorySocket socket) {
      this.socket = socket;
    }

    void send(JsonArray message) {
      socket.deliver(Json.write(message));
    }

    /**
     * Runs {@code answer} {@code delayMs} from now on the relay's loop, unless the package closed.
     */
    void later(long delayMs, Runnable answer) {
      Runnable unlessClosed =
          () -> {
            if (!socket.isClosed()) answer.run();
          };
      if (delayMs > 0) Tasks.schedule(delayMs, () -> loop.execute(unlessClosed));
      else unlessClosed.run();
    }

    void start() {
      if (behaviour.requireAuth && !behaviour.mute) send(Js.arr("AUTH", challenge));
    }

    void receive(String text) {
      Behaviour b = behaviour;
      if (b.mute) return;
      JsonArray message = Json.array(Json.parseOrNull(text));
      if (message == null || message.size() == 0) return;
      String type = Json.string(message.get(0));
      if ("AUTH".equals(type)) {
        JsonValue auth = message.size() > 1 ? message.get(1) : null;
        auths.add(auth == null ? me.sattle.checkout.internal.json.JsonNull.NULL : auth);
        NostrEvent event = Events.checked(auth);
        // NIP-42: its kind, this relay, this connection's challenge, and nothing to say.
        boolean ok =
            event != null
                && event.kind() == AUTH_KIND
                && event.content().isEmpty()
                && url.equals(event.tag("relay"))
                && challenge.equals(event.tag("challenge"));
        if (ok) {
          authed.add(event.pubkey());
          authenticated.add(event.pubkey());
        }
        JsonObject object = Json.object(auth);
        JsonArray answer =
            Js.arr(
                "OK", object == null ? null : object.get("id"), ok, ok ? "" : "invalid: bad auth");
        later(b.authDelayMs, () -> send(answer));
      } else if ("EVENT".equals(type)) {
        java.util.function.Consumer<JsonArray> answer =
            sent -> later(b.writeDelayMs, () -> send(sent));
        JsonValue value = message.size() > 1 ? message.get(1) : null;
        JsonObject object = Json.object(value);
        JsonValue id = object == null ? null : object.get("id");
        String pubkey = object == null ? null : Json.string(object.get("pubkey"));
        NostrEvent event = Events.checked(value);
        if (event != null) received.add(event);
        if (event != null && event.kind() == AUTH_KIND) {
          // An authentication is an AUTH message (NIP-42), never an event to keep.
          answer.accept(Js.arr("OK", id, false, "invalid: authenticate with an AUTH message"));
        } else if (b.requireAuth && !authed.contains(pubkey)) {
          answer.accept(Js.arr("OK", id, false, "auth-required: sign in first"));
        } else if (b.refuse != null) {
          answer.accept(Js.arr("OK", id, false, b.refuse));
        } else if (event != null && b.refuseEvent != null && b.refuseEvent.apply(event) != null) {
          answer.accept(Js.arr("OK", id, false, b.refuseEvent.apply(event)));
        } else if (event == null) {
          answer.accept(Js.arr("OK", id, false, "invalid: bad event"));
        } else {
          synchronized (LocalRelay.this) {
            keep(event);
          }
          if (b.onEvent != null) b.onEvent.accept(event, LocalRelay.this);
          answer.accept(Js.arr("OK", event.id(), true, ""));
        }
      } else if ("REQ".equals(type)) {
        JsonValue id = message.size() > 1 ? message.get(1) : null;
        List<JsonObject> filters = new ArrayList<>();
        for (int i = 2; i < message.size(); i += 1) {
          JsonObject filter = Json.object(message.get(i));
          if (filter != null) filters.add(filter);
        }
        queries.addAll(filters);
        if (b.requireAuth && authed.isEmpty()) {
          later(b.queryDelayMs, () -> send(Js.arr("CLOSED", id, "auth-required: sign in first")));
          return;
        }
        for (JsonObject filter : filters) {
          if (b.muteQuery != null && b.muteQuery.test(filter)) return;
        }
        List<JsonValue> scripted =
            b.script == null || filters.isEmpty() ? null : b.script.apply(filters.get(0));
        boolean fails = b.script != null && scripted == null;
        Runnable answer =
            () -> {
              if (socket.isClosed()) return;
              if (fails) {
                send(Js.arr("CLOSED", id, "error: this page fails"));
                return;
              }
              for (JsonValue event : scripted != null ? scripted : match(filters)) {
                send(Js.arr("EVENT", id, event));
              }
              send(Js.arr("EOSE", id));
            };
        if (b.queryDelayMs > 0) Tasks.schedule(b.queryDelayMs, () -> loop.execute(answer));
        else answer.run();
      } else if ("CLOSE".equals(type)) {
        closedSubscriptions.add(
            message.size() > 1 ? String.valueOf(Json.string(message.get(1))) : "");
      }
    }
  }

  private static String dOf(JsonObject event) {
    JsonArray tags = Json.array(event.get("tags"));
    if (tags == null) return "";
    for (JsonValue tag : tags.items()) {
      JsonArray items = Json.array(tag);
      if (items != null && items.size() > 0 && "d".equals(Json.string(items.get(0)))) {
        String d = items.size() > 1 ? Json.string(items.get(1)) : null;
        return d == null ? "" : d;
      }
    }
    return "";
  }

  /** Keeps the newest event of an address (kind 30000–39999), as relays do (NIP-01). */
  private void keep(NostrEvent event) {
    JsonObject json = event.toJson();
    if (event.kind() >= 30000 && event.kind() < 40000) {
      String d = event.tag("d") == null ? "" : event.tag("d");
      for (JsonValue other : events) {
        JsonObject o = Json.object(other);
        if (o == null
            || !Json.isNumber(o.get("kind"), (long) event.kind())
            || !event.pubkey().equals(Json.string(o.get("pubkey")))
            || !dOf(o).equals(d)) {
          continue;
        }
        Long oldDate = Json.safeInteger(o.get("created_at"));
        String oldId = Json.string(o.get("id"));
        long old = oldDate == null ? 0 : oldDate;
        // The newest kept; of one second, the lowest id.
        if (old > event.createdAt()
            || (old == event.createdAt() && oldId != null && oldId.compareTo(event.id()) <= 0)) {
          return;
        }
        events.remove(other);
        break;
      }
    }
    events.add(json);
  }

  private static boolean has(JsonObject filter, String name) {
    return filter.has(name);
  }

  private static boolean includes(JsonValue list, JsonValue value) {
    JsonArray array = Json.array(list);
    if (array == null || value == null) return false;
    for (JsonValue item : array.items()) {
      if (item.equals(value)) return true;
    }
    return false;
  }

  private List<JsonValue> match(List<JsonObject> filters) {
    Behaviour b = behaviour;
    List<JsonValue> found = new ArrayList<>();
    for (JsonValue value : events) {
      JsonObject event = Json.object(value);
      if (event == null) continue;
      if (b.hide != null && b.hide.test(value)) continue;
      boolean matches = false;
      for (JsonObject filter : filters) {
        if (has(filter, "ids") && !includes(filter.get("ids"), event.get("id"))) continue;
        if (has(filter, "kinds") && !includes(filter.get("kinds"), event.get("kind"))) continue;
        if (has(filter, "authors") && !includes(filter.get("authors"), event.get("pubkey")))
          continue;
        boolean tags = true;
        for (String name : List.of("d", "o")) {
          JsonValue values = filter.get("#" + name);
          if (values == null) continue;
          boolean any = false;
          JsonArray eventTags = Json.array(event.get("tags"));
          if (eventTags != null) {
            for (JsonValue tag : eventTags.items()) {
              JsonArray items = Json.array(tag);
              if (items != null
                  && items.size() > 1
                  && name.equals(Json.string(items.get(0)))
                  && includes(values, items.get(1))) {
                any = true;
              }
            }
          }
          if (!any) tags = false;
        }
        if (!tags) continue;
        long createdAt = dateOf(event);
        Double since = Json.number(filter.get("since"));
        Double until = Json.number(filter.get("until"));
        if (since != null && createdAt < since) continue;
        if (until != null && createdAt > until) continue;
        matches = true;
        break;
      }
      if (matches) found.add(value);
    }
    found.sort((a, c) -> {
      long byDate = Long.compare(dateOf(Json.object(c)), dateOf(Json.object(a)));
      if (byDate != 0) return (int) byDate;
      return String.valueOf(Json.string(Json.object(a).get("id")))
          .compareTo(String.valueOf(Json.string(Json.object(c).get("id"))));
    });
    boolean noLimit = true;
    for (JsonObject filter : filters) noLimit &= !filter.has("limit");
    if (b.oldestFirstWithoutLimit && noLimit) Collections.reverse(found);
    double limit = Double.POSITIVE_INFINITY;
    for (JsonObject filter : filters) {
      Double value = Json.number(filter.get("limit"));
      limit = Math.min(limit, value == null ? Double.POSITIVE_INFINITY : value);
    }
    if (b.cap != null) limit = Math.min(limit, b.cap);
    return limit >= found.size() ? found : new ArrayList<>(found.subList(0, (int) limit));
  }

  private static long dateOf(JsonObject event) {
    JsonValue date = event == null ? null : event.get("created_at");
    return date instanceof JsonNumber number ? (long) number.value() : 0;
  }

  /**
   * The events kept that are valid, as events.
   *
   * @return the valid events
   */
  public List<NostrEvent> validEvents() {
    List<NostrEvent> valid = new ArrayList<>();
    for (JsonValue value : events) {
      NostrEvent event = Events.checked(value);
      if (event != null) valid.add(event);
    }
    return valid;
  }

  /**
   * The strings of a JSON array.
   *
   * @param value an array
   * @return its strings
   */
  static List<String> strings(JsonValue value) {
    List<String> out = new ArrayList<>();
    JsonArray array = Json.array(value);
    if (array != null) {
      for (JsonValue item : array.items()) {
        if (item instanceof JsonString text) out.add(text.value());
      }
    }
    return out;
  }
}
