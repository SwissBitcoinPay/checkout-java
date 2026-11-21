package me.sattle.checkout.support;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import me.sattle.checkout.WebSocketConnection;
import me.sattle.checkout.WebSocketFactory;
import me.sattle.checkout.WebSocketListener;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * The relays of a test, by their {@code wss://} name, and the explorer's WebSocket: the WebSocket
 * factory the package gets. A name nobody serves refuses connections; a black hole takes them and
 * never answers the handshake.
 */
public final class RelayNet implements WebSocketFactory {
  /** The relays, by name. */
  public final Map<String, LocalRelay> relays = new ConcurrentHashMap<>();

  /** How many connections each name got. */
  public final Map<String, Integer> connections = new ConcurrentHashMap<>();

  /** The headers of each handshake, in order. */
  public final List<Map<String, String>> handshakes = new CopyOnWriteArrayList<>();

  /** Names that take connections and never answer the handshake. */
  public final Set<String> blackholes = ConcurrentHashMap.newKeySet();

  /** Names served by a test's own WebSocket server (the explorer's). */
  public final Map<String, ExplorerSocket> servers = new ConcurrentHashMap<>();

  /** The connections not over yet. */
  private final Set<MemorySocket> open = ConcurrentHashMap.newKeySet();

  /** A new network of relays. */
  public RelayNet() {}

  /**
   * Starts a relay at {@code url}.
   *
   * @param url its name
   * @param behaviour what it does
   * @return the relay
   */
  public LocalRelay add(String url, LocalRelay.Behaviour behaviour) {
    LocalRelay relay = new LocalRelay(url, behaviour);
    relays.put(url, relay);
    return relay;
  }

  /**
   * Starts a relay at {@code url} that does nothing special.
   *
   * @param url its name
   * @return the relay
   */
  public LocalRelay add(String url) {
    return add(url, new LocalRelay.Behaviour());
  }

  /**
   * The relay of {@code url}.
   *
   * @param url its name
   * @return the relay
   */
  public LocalRelay get(String url) {
    LocalRelay relay = relays.get(url);
    if (relay == null) throw new IllegalArgumentException("No relay " + url);
    return relay;
  }

  /**
   * Stops the relay of {@code url}: its connections end, its name refuses connections.
   *
   * @param url its name
   */
  public void remove(String url) {
    LocalRelay relay = get(url);
    relays.remove(url);
    relay.terminateAll();
  }

  /**
   * A WebSocket server of the test at {@code url} (the explorer's).
   *
   * @param url its name
   * @return the server
   */
  public ExplorerSocket addServer(String url) {
    ExplorerSocket server = new ExplorerSocket();
    servers.put(url, server);
    return server;
  }

  /**
   * Waits until every connection is over, 5 s at most: a call returns once a majority of the relays
   * took its writes, and closes its connections once the others' writes are over too (bounded by
   * their timeout); a test that reads every relay waits for them.
   *
   * @throws InterruptedException when the test is interrupted
   */
  public void settle() throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      open.removeIf(MemorySocket::isOver);
      if (open.isEmpty()) return;
      Thread.sleep(5);
    }
  }

  /** Ends every connection. */
  public void close() {
    for (LocalRelay relay : relays.values()) relay.terminateAll();
    for (ExplorerSocket server : servers.values()) server.drop();
  }

  @Override
  public WebSocketConnection open(
      URI uri, Map<String, String> headers, Duration handshakeTimeout, WebSocketListener listener) {
    String url = uri.toString();
    connections.merge(url, 1, Integer::sum);
    handshakes.add(Map.copyOf(headers));
    MemorySocket socket = new MemorySocket(listener);
    open.add(socket);
    LocalRelay relay = relays.get(url);
    ExplorerSocket server = servers.get(url);
    if (relay != null) {
      relay.accept(socket);
    } else if (server != null) {
      server.accept(socket);
    } else if (blackholes.contains(url)) {
      Tasks.schedule(
          handshakeTimeout.toMillis(),
          () -> socket.terminate(new IOException("the handshake ran out of time")));
    } else {
      socket.terminate(new IOException("connection refused"));
    }
    return socket;
  }

  /**
   * mempool.space's WebSocket for tests: records what each client sends, and pushes what a test
   * says changed.
   */
  public static final class ExplorerSocket {
    /** What the clients sent, parsed. */
    public final List<JsonValue> messages = new CopyOnWriteArrayList<>();

    private final List<MemorySocket> clients = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger connections =
        new java.util.concurrent.atomic.AtomicInteger();

    ExplorerSocket() {}

    void accept(MemorySocket socket) {
      connections.incrementAndGet();
      clients.add(socket);
      socket.serve(
          text -> {
            JsonValue message = Json.parseOrNull(text);
            if (message != null) messages.add(message);
          },
          () -> clients.remove(socket));
      socket.opened();
    }

    /**
     * How many connections it got.
     *
     * @return the count
     */
    public int connections() {
      return connections.get();
    }

    /**
     * How many clients are connected.
     *
     * @return the count
     */
    public int clients() {
      List<MemorySocket> open = new ArrayList<>();
      for (MemorySocket client : clients) if (!client.isClosed()) open.add(client);
      return open.size();
    }

    /**
     * Pushes {@code message} to every client.
     *
     * @param message the message
     */
    public void push(JsonValue message) {
      for (MemorySocket client : clients) client.deliver(Json.write(message));
    }

    /** Drops every client's connection. */
    public void drop() {
      for (MemorySocket client : clients) client.terminate(new IOException("dropped"));
      clients.clear();
    }
  }
}
