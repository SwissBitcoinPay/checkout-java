package me.sattle.checkout.internal.onchain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import me.sattle.checkout.WebSocketConnection;
import me.sattle.checkout.WebSocketFactory;
import me.sattle.checkout.WebSocketListener;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.SerialExecutor;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;

/**
 * The explorer's WebSocket (SPEC.md §31, "Waiting for an on-chain payment"): while {@code
 * waitForPayment} waits, the explorer (mempool.space, or the merchant's Bitcoin node) pushes what
 * changes for the address (a transaction entering the mempool, confirmed, or leaving it) and each
 * new block (one more confirmation). Only a signal to check the on-chain part again: what it pushes
 * is never trusted, the check asks the explorer.
 */
public final class Watch implements AutoCloseable {
  /** The messages that say something changed for the address, or for its confirmations. */
  private static final List<String> CHANGES =
      List.of(
          "address-transactions", "block-transactions", "address-removed-transactions", "block");

  private final WebSocketFactory sockets;
  private final Duration handshakeTimeout;
  private final CallExplorer explorer;
  private final String address;
  private final Runnable onChange;

  /** Every state of the watcher lives on it. */
  private final SerialExecutor loop = new SerialExecutor();

  /** The delay after a connection that received a message was lost, and the longest delay. */
  private final long firstDelayMs;

  private final long maxDelayMs;

  private boolean closed;
  private Attempt current;
  private ScheduledFuture<?> timer;
  private long delay;

  private Watch(
      WebSocketFactory sockets,
      Duration handshakeTimeout,
      CallExplorer explorer,
      String address,
      Runnable onChange,
      long firstDelayMs,
      long maxDelayMs) {
    this.sockets = sockets;
    this.handshakeTimeout = handshakeTimeout;
    this.explorer = explorer;
    this.address = address;
    this.onChange = onChange;
    this.firstDelayMs = firstDelayMs;
    this.maxDelayMs = maxDelayMs;
    this.delay = firstDelayMs;
  }

  /**
   * Watches {@code address} on the explorer's WebSocket until {@link #close}: {@code onChange} is
   * called at each connection (what happened before was not pushed) and at each message about the
   * address or a new block. Each connection tries the WebSockets of {@code explorer}'s sources in
   * order ({@link CallExplorer#sources}): one that fails, or is lost before any message, goes to
   * the next at once (the node's to mempool.space's with {@code fallback} {@code "mempool"}, a node
   * that failed less than 60 s ago in the call tried after it; with {@code "none"}, the node's
   * alone), and a failure of the node's is the call's ({@link CallExplorer#nodeFailed}). A lost
   * socket connects again after 1 s, doubled at each failure (of the last one tried) up to 30 s: a
   * connection lost before it received a message is a failure (each connection starts a check: a
   * server that accepts and drops every connection is not connected to every second), one that
   * received a message sets the delay back to 1 s.
   *
   * @param sockets opens the WebSocket
   * @param handshakeTimeout the bound of its opening handshake (the relay timeout)
   * @param explorer the explorer of the call
   * @param address the address
   * @param onChange what to call (it must not block)
   * @return the watcher
   */
  public static Watch watchAddress(
      WebSocketFactory sockets,
      Duration handshakeTimeout,
      CallExplorer explorer,
      String address,
      Runnable onChange) {
    return watchAddress(
        sockets,
        handshakeTimeout,
        explorer,
        address,
        onChange,
        Constants.RECONNECT_FIRST_MS,
        Constants.RECONNECT_MAX_MS);
  }

  /**
   * {@link #watchAddress(WebSocketFactory, Duration, CallExplorer, String, Runnable)} with delays
   * of its own (for tests): {@code firstDelayMs} after the loss of a connection that received a
   * message, doubled at each failure up to {@code maxDelayMs}.
   *
   * @param sockets opens the WebSocket
   * @param handshakeTimeout the bound of its opening handshake
   * @param explorer the explorer of the call
   * @param address the address
   * @param onChange what to call (it must not block)
   * @param firstDelayMs the first delay
   * @param maxDelayMs the longest delay
   * @return the watcher
   */
  public static Watch watchAddress(
      WebSocketFactory sockets,
      Duration handshakeTimeout,
      CallExplorer explorer,
      String address,
      Runnable onChange,
      long firstDelayMs,
      long maxDelayMs) {
    Watch watch =
        new Watch(sockets, handshakeTimeout, explorer, address, onChange, firstDelayMs, maxDelayMs);
    watch.loop.execute(watch::connect);
    return watch;
  }

  /** One connection, and what it heard. */
  private final class Attempt implements WebSocketListener {
    /** The explorers this connection tries, and the one it is. */
    final List<Explorer.Source> sources;

    final int index;
    WebSocketConnection socket;
    boolean lost;

    /** Whether it received a message: a loss is then no failure. */
    boolean answered;

    Attempt(List<Explorer.Source> sources, int index) {
      this.sources = sources;
      this.index = index;
    }

    @Override
    public void onOpen() {
      loop.execute(this::opened);
    }

    @Override
    public CompletionStage<?> onText(String message) {
      loop.execute(() -> heard(message));
      return null;
    }

    @Override
    public void onClose(Throwable error) {
      loop.execute(this::lose);
    }

    private void opened() {
      if (current != this || closed) return;
      // Not the delay: a connection lost before a message is a failure.
      try {
        socket.send(
            Json.write(
                new JsonObject()
                    .put("action", "want")
                    .put("data", JsonArray.ofStrings(List.of("blocks")))));
        socket.send(Json.write(new JsonObject().put("track-address", address)));
      } catch (RuntimeException e) {
        lose();
        return;
      }
      onChange.run();
    }

    private void heard(String text) {
      if (current != this || closed) return;
      // The explorer answers: the next loss waits the first delay again.
      answered = true;
      delay = firstDelayMs;
      JsonObject message = Json.object(Json.parseOrNull(text));
      if (message == null) return;
      for (String change : CHANGES) {
        if (message.has(change)) {
          onChange.run();
          return;
        }
      }
    }

    private void lose() {
      if (lost || current != this) return;
      lost = true;
      current = null;
      if (answered) retry();
      else failed();
    }

    /** It failed, or was lost before any message: the next explorer at once, or later. */
    private void failed() {
      if (sources.get(index).isNode()) explorer.nodeFailed();
      connectTo(sources, index + 1);
    }
  }

  private void retry() {
    if (closed || timer != null) return;
    timer =
        Tasks.schedule(
            delay,
            () ->
                loop.execute(() -> {
                  timer = null;
                  connect();
                }));
    delay = Math.min(delay * 2, maxDelayMs);
  }

  private void connect() {
    connectTo(explorer.sources(), 0);
  }

  /** Connects to the WebSocket of {@code sources}' {@code index}th, the next one when it fails. */
  private void connectTo(List<Explorer.Source> sources, int index) {
    if (closed) return;
    if (index >= sources.size()) {
      retry();
      return;
    }
    Attempt attempt = new Attempt(sources, index);
    try {
      attempt.socket =
          sockets.open(
              Urls.webSocketUri(sources.get(index).webSocket()),
              Map.of("User-Agent", Constants.USER_AGENT),
              handshakeTimeout,
              attempt);
    } catch (RuntimeException e) {
      attempt.failed();
      return;
    }
    // Its news come on this loop, after this task: the attempt is current before any of them.
    current = attempt;
  }

  /** Stops watching: the socket closed, no more {@code onChange}. */
  @Override
  public void close() {
    loop.execute(() -> {
      closed = true;
      if (timer != null) {
        timer.cancel(false);
        timer = null;
      }
      Attempt attempt = current;
      current = null;
      if (attempt != null && attempt.socket != null) {
        try {
          attempt.socket.close();
        } catch (RuntimeException e) {
          // Already closed.
        }
      }
    });
  }
}
