package me.sattle.checkout;

import java.util.concurrent.CompletionStage;

/** Who hears a WebSocket a {@link WebSocketFactory} opened: one call at a time, in order. */
public interface WebSocketListener {
  /** The connection is open: messages may be sent. */
  void onOpen();

  /**
   * A whole text message (1 MiB at most). The next message is asked for once the returned stage
   * completes (null: at once): what a relay sends never piles up.
   *
   * @param message the message
   * @return when the listener is ready for the next one, or null
   */
  CompletionStage<?> onText(String message);

  /**
   * The connection ended or failed, its handshake included: called once, and nothing after.
   *
   * @param error why it failed, or null when it was closed
   */
  void onClose(Throwable error);
}
