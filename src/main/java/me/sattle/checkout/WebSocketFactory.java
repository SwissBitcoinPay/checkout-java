package me.sattle.checkout;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import me.sattle.checkout.internal.JdkWebSockets;

/**
 * Opens the WebSockets of the package: to the merchant's Nostr relays, and to the explorer's
 * WebSocket while {@link SattlemeClient#waitForPayment} waits (mempool.space's, or the merchant's
 * Bitcoin node's when the integration's channel names one: {@code ws://} to a node of a local
 * network, SPEC.md §31). Injectable (a proxy, a test); the default, {@link #jdk(HttpClient)}, is
 * the platform's {@link java.net.http.WebSocket}.
 *
 * <p>A factory MUST send {@code headers} in the opening handshake, in place of any the client would
 * send of the same names ({@code User-Agent: sattleme-Checkout/1}: some relays refuse a handshake
 * without it), follow no redirect of the handshake (a redirect is a failed connection: it would
 * skip the checks of the URL, SPEC.md §13), and SHOULD bound the handshake by {@code
 * handshakeTimeout}, offer no compression (permessage-deflate) and fail a connection whose message
 * is larger than 1 MiB (SPEC.md §13, "Limits").
 */
@FunctionalInterface
public interface WebSocketFactory {
  /**
   * Opens a WebSocket to {@code uri}: what happens is told to {@code listener}, one call at a time
   * per connection, in order. Called from a thread of the package's, never the caller's: it may
   * block while it resolves the host's name, the package's timeouts do not wait for it (SPEC.md
   * §13: the wait for the connection is within the relay timeout).
   *
   * @param uri a {@code wss://} URL (checked: SPEC.md §9), or the merchant's Bitcoin node's
   *     WebSocket, {@code ws://} or {@code wss://} (a node URL, checked: SPEC.md §31)
   * @param headers the headers of the opening handshake
   * @param handshakeTimeout the longest the opening handshake may take
   * @param listener who hears the connection
   * @return the connection, connecting
   */
  WebSocketConnection open(
      URI uri, Map<String, String> headers, Duration handshakeTimeout, WebSocketListener listener);

  /**
   * The platform's WebSocket ({@link java.net.http.WebSocket}) over {@code client}: no redirect
   * followed (the client must follow none: {@link HttpClient.Redirect#NEVER}), no compression
   * offered (the platform's offers none), the handshake bounded, a message larger than 1 MiB
   * failing the connection.
   *
   * @param client the HTTP client the WebSockets are made with
   * @return the factory
   * @throws InvalidOptionException ({@code webSocketFactory}) when {@code client} is null
   */
  static WebSocketFactory jdk(HttpClient client) {
    if (client == null) {
      throw new InvalidOptionException("webSocketFactory", "the WebSockets of an HttpClient");
    }
    return new JdkWebSockets(client);
  }
}
