package me.sattle.checkout.internal.nostr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import me.sattle.checkout.WebSocketConnection;
import me.sattle.checkout.WebSocketListener;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.SerialExecutor;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * One connection to one relay, shared by every request of a call. What its socket tells is handed
 * to the call's event loop, where every state of the relays lives; the next message is asked for
 * once the loop read the last one.
 */
final class Connection implements WebSocketListener {
  final String url;
  private final SerialExecutor loop;
  private WebSocketConnection socket;
  private final Set<Consumer<JsonArray>> listeners = new LinkedHashSet<>();
  private final Set<Consumer<Exception>> deathListeners = new LinkedHashSet<>();

  /** NIP-42 answers, by public key and challenge: one AUTH per key per challenge. */
  private final Map<String, CompletableFuture<Boolean>> auths = new HashMap<>();

  private String challenge;
  private Exception dead;

  /** Completes once the socket is open, fails if it dies first. On the loop. */
  final CompletableFuture<Void> opened = new CompletableFuture<>();

  Connection(String url, SerialExecutor loop) {
    this.url = url;
    this.loop = loop;
  }

  /** Whether the socket said it is open (it may say so before the factory returned it). */
  private boolean openSeen;

  /**
   * The socket, once the factory returned it (null: it failed), on the loop: the connection is open
   * once its socket is given and said it is open. Over meanwhile: the socket closed.
   */
  void attach(WebSocketConnection socket) {
    if (socket == null) {
      die(new RelayException(url + ": connection failed"));
      return;
    }
    if (dead != null) {
      try {
        socket.close();
      } catch (RuntimeException e) {
        // Already closed.
      }
      return;
    }
    this.socket = socket;
    if (openSeen) opened.complete(null);
  }

  @Override
  public void onOpen() {
    loop.execute(() -> {
      openSeen = true;
      if (socket != null && dead == null) opened.complete(null);
    });
  }

  @Override
  public CompletionStage<?> onText(String message) {
    CompletableFuture<Void> read = new CompletableFuture<>();
    loop.execute(() -> {
      try {
        receive(message);
      } finally {
        read.complete(null);
      }
    });
    return read;
  }

  @Override
  public void onClose(Throwable error) {
    loop.execute(() ->
        die(new RelayException(url + ": connection " + (error == null ? "closed" : "failed"))));
  }

  private void receive(String text) {
    JsonArray message = Json.array(Json.parseOrNull(text));
    if (message == null || dead != null) return;
    if (message.size() >= 2 && "AUTH".equals(Json.string(message.get(0)))) {
      String value = Json.string(message.get(1));
      if (value != null) challenge = value;
    }
    for (Consumer<JsonArray> listener : new ArrayList<>(listeners)) {
      if (dead != null) return;
      listener.accept(message);
    }
  }

  /** Hears every message of the relay until the returned task runs. */
  Runnable listen(Consumer<JsonArray> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /** Hears the end of the connection (now, when it is over already). */
  Runnable onDeath(Consumer<Exception> listener) {
    if (dead != null) {
      listener.accept(dead);
      return () -> {};
    }
    deathListeners.add(listener);
    return () -> deathListeners.remove(listener);
  }

  void send(JsonArray message) {
    if (dead != null || socket == null) return;
    try {
      socket.send(Json.write(message));
    } catch (RuntimeException e) {
      die(new RelayException(url + ": connection failed"));
    }
  }

  /**
   * Answers the relay's NIP-42 challenge with {@code authKey}: whether the relay took it. One AUTH
   * per key and challenge, shared by the requests that ask for it.
   */
  CompletableFuture<Boolean> authenticate(byte[] authKey, long createdAt, RandomBytes random) {
    String current = challenge;
    if (dead != null || current == null) return CompletableFuture.completedFuture(false);
    String id = me.sattle.checkout.internal.crypto.Curve.publicKeyOf(authKey) + ":" + current;
    CompletableFuture<Boolean> answer = auths.get(id);
    if (answer == null) {
      NostrEvent auth =
          Events.sign(
              Constants.AUTH_KIND,
              createdAt,
              List.of(List.of("relay", url), List.of("challenge", current)),
              "",
              authKey,
              random);
      CompletableFuture<Boolean> done = new CompletableFuture<>();
      Runnable[] stops = new Runnable[2];
      stops[0] =
          listen(message -> {
            if (message.size() < 3
                || !"OK".equals(Json.string(message.get(0)))
                || !auth.id().equals(Json.string(message.get(1)))) {
              return;
            }
            stops[0].run();
            stops[1].run();
            done.complete(Json.isTrue(message.get(2)));
          });
      stops[1] =
          onDeath(error -> {
            stops[0].run();
            done.complete(false);
          });
      answer = done;
      auths.put(id, answer);
      send(new JsonArray().add("AUTH").add(auth.toJson()));
    }
    return answer;
  }

  /** Closes the connection; every request still on it fails. */
  void close() {
    WebSocketConnection current = socket;
    if (current != null) {
      try {
        current.close();
      } catch (RuntimeException e) {
        // Already closed.
      }
    }
    die(new RelayException(url + ": connection closed"));
  }

  private void die(Exception error) {
    if (dead != null) return;
    dead = error;
    listeners.clear();
    opened.completeExceptionally(error);
    for (Consumer<Exception> listener : new ArrayList<>(deathListeners)) listener.accept(error);
    deathListeners.clear();
  }

  boolean isDead() {
    return dead != null;
  }

  /** A JSON value of what the relay sent, for messages of a subscription. */
  static String stringAt(JsonArray message, int index) {
    return message.size() > index ? Json.string(message.get(index)) : null;
  }

  static JsonValue at(JsonArray message, int index) {
    return message.size() > index ? message.get(index) : null;
  }
}
