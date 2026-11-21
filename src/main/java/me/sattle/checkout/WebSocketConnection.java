package me.sattle.checkout;

/** A WebSocket a {@link WebSocketFactory} opened. */
public interface WebSocketConnection {
  /**
   * Sends a text message, once the connection is open (messages are sent in order; a failure is
   * told to the listener as the end of the connection).
   *
   * @param text the message
   */
  void send(String text);

  /** Closes the connection (or abandons its handshake): nothing more is told to its listener. */
  void close();
}
