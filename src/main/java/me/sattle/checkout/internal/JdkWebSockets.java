package me.sattle.checkout.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import me.sattle.checkout.WebSocketConnection;
import me.sattle.checkout.WebSocketFactory;
import me.sattle.checkout.WebSocketListener;

/**
 * WebSockets over {@link java.net.http.WebSocket} (SPEC.md §13): the opening handshake bounded by
 * the relay timeout (the resolution of the host's name included, never on the caller's thread), its
 * headers (the User-Agent of every implementation, in place of the platform's own {@code
 * Java-http-client/…}), no redirect followed (the client follows none), no compression offered (the
 * platform implements none); each message assembled from its parts up to 1 MiB, beyond which the
 * connection fails; the next message asked for once the listener is ready.
 */
public final class JdkWebSockets implements WebSocketFactory {
  private final HttpClient client;

  /**
   * WebSockets made with {@code client}.
   *
   * @param client an HTTP client that follows no redirect
   */
  public JdkWebSockets(HttpClient client) {
    this.client = client;
  }

  @Override
  public WebSocketConnection open(
      URI uri, Map<String, String> headers, Duration handshakeTimeout, WebSocketListener listener) {
    Handle handle = new Handle(listener);
    // The whole handshake, from the resolution of the host's name on: the platform's connect
    // timeout cannot cut a name's resolution short (nothing bounds it).
    ScheduledFuture<?> timer =
        Tasks.schedule(Math.max(1, handshakeTimeout.toMillis()), handle::handshakeTimedOut);
    // Built from a thread of the package's: a client may resolve the host's name on the calling
    // thread before buildAsync returns (the platform's does, with an executor that runs its tasks
    // there); neither the caller nor the timer waits for it.
    Tasks.WORKERS.execute(() -> {
      if (handle.isOver()) return;
      CompletableFuture<WebSocket> opening;
      try {
        WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(handshakeTimeout);
        for (Map.Entry<String, String> header : headers.entrySet()) {
          builder.header(header.getKey(), header.getValue());
        }
        opening = builder.buildAsync(uri, handle);
      } catch (RuntimeException e) {
        timer.cancel(false);
        handle.end(e);
        return;
      }
      opening.whenComplete((socket, error) -> {
        timer.cancel(false);
        if (error != null) handle.end(error);
        else handle.opened(socket);
      });
    });
    return handle;
  }

  /** One connection: the platform's listener, and what the package sends and closes. */
  private static final class Handle implements WebSocket.Listener, WebSocketConnection {
    private final WebSocketListener listener;
    private WebSocket socket;
    private boolean closed;
    private boolean ended;
    private boolean told;

    /** The message being assembled from its parts. */
    private StringBuilder text = new StringBuilder();

    /** Its size in UTF-8 bytes so far. */
    private long bytes;

    /** A high surrogate that ended a part: its pair's 4 bytes counted with the next. */
    private boolean pendingHigh;

    /** Sends, one after the other (the platform allows one at a time). */
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

    Handle(WebSocketListener listener) {
      this.listener = listener;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      synchronized (this) {
        socket = webSocket;
        if (closed || ended) {
          webSocket.abort();
          return;
        }
      }
      tellOpen();
      webSocket.request(1);
    }

    /** The handshake's future completed: the connection may already be told open. */
    void opened(WebSocket webSocket) {
      synchronized (this) {
        socket = webSocket;
        if (closed || ended) {
          webSocket.abort();
          return;
        }
      }
      tellOpen();
    }

    /**
     * The handshake took longer than its bound: the connection ended, unless it was told open
     * already; a socket that opens later is aborted.
     */
    void handshakeTimedOut() {
      WebSocket target;
      boolean tell;
      synchronized (this) {
        if (told || ended) return;
        ended = true;
        target = socket;
        tell = !closed;
      }
      if (target != null) target.abort();
      if (tell) {
        listener.onClose(new HttpTimeoutException("The opening handshake took too long"));
      }
    }

    private void tellOpen() {
      synchronized (this) {
        if (told || ended) return;
        told = true;
      }
      listener.onOpen();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (!count(data)) {
        webSocket.abort();
        end(new IllegalStateException("A message larger than 1 MiB"));
        return null;
      }
      text.append(data);
      if (!last) {
        webSocket.request(1);
        return null;
      }
      String message = text.toString();
      text = new StringBuilder();
      bytes = 0;
      pendingHigh = false;
      if (isEnded()) return null;
      CompletionStage<?> ready = listener.onText(message);
      if (ready == null) webSocket.request(1);
      else ready.whenComplete((ignored, error) -> webSocket.request(1));
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      // Relays and the explorer send text: a binary message is skipped, its size bounded too.
      bytes += data.remaining();
      if (bytes > Constants.MAX_RELAY_MESSAGE_BYTES) {
        webSocket.abort();
        end(new IllegalStateException("A message larger than 1 MiB"));
        return null;
      }
      if (last) bytes = 0;
      webSocket.request(1);
      return null;
    }

    /** Adds the UTF-8 size of {@code data} to the message's: false once above 1 MiB. */
    private boolean count(CharSequence data) {
      int length = data.length();
      for (int i = 0; i < length; i += 1) {
        char c = data.charAt(i);
        if (pendingHigh) {
          pendingHigh = false;
          // The pair's 4 bytes were counted at its high surrogate.
          if (Character.isLowSurrogate(c)) continue;
        }
        if (c < 0x80) {
          bytes += 1;
        } else if (c < 0x800) {
          bytes += 2;
        } else if (Character.isHighSurrogate(c)) {
          bytes += 4;
          pendingHigh = true;
        } else {
          bytes += 3;
        }
      }
      return bytes <= Constants.MAX_RELAY_MESSAGE_BYTES;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      end(null);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      end(error);
    }

    private synchronized boolean isEnded() {
      return ended;
    }

    /** Ended, or closed by the package. */
    synchronized boolean isOver() {
      return ended || closed;
    }

    /** The end of the connection, told once (never after a close by the package). */
    void end(Throwable error) {
      synchronized (this) {
        if (ended) return;
        ended = true;
        if (closed) return;
      }
      listener.onClose(error);
    }

    @Override
    public void send(String message) {
      WebSocket target;
      synchronized (this) {
        if (closed || ended || socket == null) return;
        target = socket;
        sending =
            sending
                .thenCompose(ignored -> target.sendText(message, true))
                .whenComplete((ignored, error) -> {
                  if (error != null) {
                    target.abort();
                    end(error);
                  }
                });
      }
    }

    @Override
    public void close() {
      WebSocket target;
      synchronized (this) {
        if (closed) return;
        closed = true;
        target = socket;
        if (target == null) return;
        // The close frame after what was sent, then the connection dropped: nothing waits for the
        // relay's own close.
        sending
            .handle((ignored, error) -> null)
            .thenCompose(ignored -> target.sendClose(WebSocket.NORMAL_CLOSURE, ""))
            .whenComplete((ignored, error) -> target.abort());
      }
    }
  }
}
