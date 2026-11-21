package me.sattle.checkout.support;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import me.sattle.checkout.WebSocketConnection;
import me.sattle.checkout.WebSocketListener;
import me.sattle.checkout.internal.SerialExecutor;

/**
 * A WebSocket held in memory between the package and a test's server (a relay, the explorer's
 * WebSocket): what each side sends reaches the other on a thread of its own, in order, the next
 * message only once the package read the last one.
 */
public final class MemorySocket implements WebSocketConnection {
  private final WebSocketListener listener;
  private final SerialExecutor toClient = new SerialExecutor();
  private volatile Consumer<String> server = text -> {};
  private volatile Runnable onClientClose = () -> {};
  private volatile boolean closed;
  private volatile boolean ended;

  MemorySocket(WebSocketListener listener) {
    this.listener = listener;
  }

  /** Who hears what the package sends, and its close. */
  void serve(Consumer<String> server, Runnable onClientClose) {
    this.server = server;
    this.onClientClose = onClientClose;
  }

  /** The handshake is over: the package may send. */
  void opened() {
    toClient.execute(() -> {
      if (!closed && !ended) listener.onOpen();
    });
  }

  /** Sends {@code text} to the package, once it read what came before. */
  public void deliver(String text) {
    toClient.execute(() -> {
      if (closed || ended) return;
      CompletionStage<?> read = listener.onText(text);
      if (read == null) return;
      try {
        read.toCompletableFuture().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        // Read all the same.
      }
    });
  }

  /** Ends the connection from the server's side (a relay stopped, a socket dropped). */
  public void terminate(Throwable error) {
    toClient.execute(() -> {
      if (closed || ended) return;
      ended = true;
      listener.onClose(error);
    });
  }

  /**
   * Whether the package closed it.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Whether it is over: closed by the package, or ended by the server.
   *
   * @return true once over
   */
  public boolean isOver() {
    return closed || ended;
  }

  @Override
  public void send(String text) {
    if (closed || ended) return;
    server.accept(text);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    onClientClose.run();
  }
}
