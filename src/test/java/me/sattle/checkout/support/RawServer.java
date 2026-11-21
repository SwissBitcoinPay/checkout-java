package me.sattle.checkout.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A server on 127.0.0.1 that speaks HTTP/1.1 and RFC 6455 by hand, so that a test sees the bytes
 * the platform's clients send and answers what no well-behaved server would: a redirect, a stalled
 * body, a message in parts larger than 1 MiB, an extension nobody asked for. Each connection runs
 * {@link Handler} on a thread of its own.
 */
public final class RawServer implements AutoCloseable {
  /** What the server does with a connection, once its request was read. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Answers {@code request} on {@code socket}.
     *
     * @param socket the connection
     * @param request what the client sent
     * @throws IOException when the connection fails
     * @throws InterruptedException when the server stops
     */
    void handle(Socket socket, Request request) throws IOException, InterruptedException;
  }

  /**
   * A request: its first line and its header lines, as sent.
   *
   * @param line the request line
   * @param headers the header lines, each {@code [name, value]}
   */
  public record Request(String line, List<String[]> headers) {
    /**
     * The values of the headers named {@code name} (whatever its case), in order.
     *
     * @param name a header's name
     * @return its values
     */
    public List<String> all(String name) {
      List<String> values = new ArrayList<>();
      for (String[] header : headers) {
        if (header[0].equalsIgnoreCase(name)) values.add(header[1]);
      }
      return values;
    }

    /**
     * The value of the first header named {@code name}.
     *
     * @param name a header's name
     * @return its value, or null
     */
    public String first(String name) {
      List<String> values = all(name);
      return values.isEmpty() ? null : values.get(0);
    }
  }

  private final ServerSocket server;
  private final Handler handler;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();

  /** The requests the server read, in order. */
  public final List<Request> requests = new CopyOnWriteArrayList<>();

  /**
   * Starts a server on 127.0.0.1, on a free port.
   *
   * @param handler what it does with each connection
   * @throws IOException when it cannot listen
   */
  public RawServer(Handler handler) throws IOException {
    this.handler = handler;
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread acceptor = new Thread(this::accept, "raw-server-" + server.getLocalPort());
    acceptor.setDaemon(true);
    acceptor.start();
  }

  /**
   * Its port.
   *
   * @return the port
   */
  public int port() {
    return server.getLocalPort();
  }

  /**
   * Its URL with {@code scheme} ({@code http}, {@code ws}).
   *
   * @param scheme the scheme
   * @return the URL
   */
  public String url(String scheme) {
    return scheme + "://127.0.0.1:" + port() + "/";
  }

  /**
   * How many connections it accepted.
   *
   * @return their count
   */
  public int connections() {
    return sockets.size();
  }

  private void accept() {
    while (!server.isClosed()) {
      Socket socket;
      try {
        socket = server.accept();
      } catch (IOException e) {
        return;
      }
      sockets.add(socket);
      Thread thread =
          new Thread(
              () -> {
                try (socket) {
                  Request request = readRequest(socket.getInputStream());
                  if (request == null) return;
                  requests.add(request);
                  handler.handle(socket, request);
                } catch (IOException | InterruptedException e) {
                  // The connection ended.
                }
              },
              "raw-connection");
      thread.setDaemon(true);
      thread.start();
    }
  }

  /** A request's head, read up to its empty line; null when the connection ends first. */
  private static Request readRequest(InputStream in) throws IOException {
    List<String> lines = new ArrayList<>();
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int previous = -1;
    for (; ; ) {
      int b = in.read();
      if (b < 0) return null;
      if (previous == '\r' && b == '\n') {
        byte[] bytes = line.toByteArray();
        String text = new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
        if (text.isEmpty()) break;
        lines.add(text);
        line.reset();
        previous = -1;
        continue;
      }
      line.write(b);
      previous = b;
    }
    List<String[]> headers = new ArrayList<>();
    for (String header : lines.subList(1, lines.size())) {
      int colon = header.indexOf(':');
      headers.add(
          new String[] {header.substring(0, colon).trim(), header.substring(colon + 1).trim()});
    }
    return new Request(lines.get(0), headers);
  }

  /**
   * Writes {@code text} in ISO-8859-1 (an HTTP head) and flushes.
   *
   * @param socket the connection
   * @param text the text
   * @throws IOException when the connection fails
   */
  public static void write(Socket socket, String text) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  /**
   * Accepts a WebSocket handshake (RFC 6455 §4.2.2): 101, its {@code Sec-WebSocket-Accept}.
   *
   * @param socket the connection
   * @param request the handshake
   * @param extraHeaders more header lines, each ending with CRLF ("" for none)
   * @throws IOException when the connection fails
   */
  public static void acceptWebSocket(Socket socket, Request request, String extraHeaders)
      throws IOException {
    String key = request.first("Sec-WebSocket-Key");
    String accept;
    try {
      accept =
          Base64.getEncoder()
              .encodeToString(
                  MessageDigest.getInstance("SHA-1")
                      .digest(
                          (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                              .getBytes(StandardCharsets.ISO_8859_1)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    write(
        socket,
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + accept
            + "\r\n"
            + extraHeaders
            + "\r\n");
  }

  /**
   * Sends a frame from the server (unmasked).
   *
   * @param socket the connection
   * @param opcode 1 for text, 0 for a continuation, 8 for a close
   * @param fin whether it is the message's last frame
   * @param payload its bytes
   * @throws IOException when the connection fails
   */
  public static void frame(Socket socket, int opcode, boolean fin, byte[] payload)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write((fin ? 0x80 : 0) | opcode);
    if (payload.length < 126) {
      out.write(payload.length);
    } else if (payload.length < 65_536) {
      out.write(126);
      out.write(payload.length >>> 8);
      out.write(payload.length & 0xff);
    } else {
      out.write(127);
      long length = payload.length;
      for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (length >>> shift) & 0xff);
    }
    out.write(payload, 0, payload.length);
    OutputStream stream = socket.getOutputStream();
    stream.write(out.toByteArray());
    stream.flush();
  }

  /**
   * Sends a text message in frames of the given parts.
   *
   * @param socket the connection
   * @param parts the text of each frame
   * @throws IOException when the connection fails
   */
  public static void text(Socket socket, String... parts) throws IOException {
    for (int i = 0; i < parts.length; i += 1) {
      frame(
          socket, i == 0 ? 1 : 0, i == parts.length - 1, parts[i].getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Reads frames from the client until the connection ends, keeping the text messages.
   *
   * @param socket the connection
   * @param messages where the client's text messages go
   */
  public static void readClient(Socket socket, List<String> messages) {
    try {
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream message = new ByteArrayOutputStream();
      for (; ; ) {
        int first = in.read();
        int second = in.read();
        if (first < 0 || second < 0) return;
        long length = second & 0x7f;
        if (length == 126) length = (in.read() << 8) | in.read();
        else if (length == 127) {
          length = 0;
          for (int i = 0; i < 8; i += 1) length = (length << 8) | in.read();
        }
        byte[] mask = in.readNBytes((second & 0x80) != 0 ? 4 : 0);
        byte[] payload = in.readNBytes((int) length);
        for (int i = 0; i < payload.length && mask.length == 4; i += 1) payload[i] ^= mask[i % 4];
        int opcode = first & 0x0f;
        if (opcode == 8) return;
        if (opcode == 1 || opcode == 0) {
          message.write(payload, 0, payload.length);
          if ((first & 0x80) != 0) {
            messages.add(message.toString(StandardCharsets.UTF_8));
            message.reset();
          }
        }
      }
    } catch (IOException e) {
      // The connection ended.
    }
  }

  /**
   * Waits until the connection ends (the client closed it), reading what it sends.
   *
   * @param socket the connection
   */
  public static void drain(Socket socket) {
    readClient(socket, new ArrayList<>());
  }

  @Override
  public void close() {
    try {
      server.close();
    } catch (IOException e) {
      // Closed.
    }
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException e) {
        // Closed.
      }
    }
  }

  @Override
  public String toString() {
    return "RawServer[" + port() + "]";
  }
}
