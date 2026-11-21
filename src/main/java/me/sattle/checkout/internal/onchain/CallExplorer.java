package me.sattle.checkout.internal.onchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Tasks;

/**
 * The explorer of one call (SPEC.md §31, "Explorers", {@code fallback}): mempool.space, or the
 * channel's node with its fallback. Each read follows the fallback on its own; a node that fails is
 * remembered in the call's {@link Failures}, which its reads (and its WebSocket, {@link Watch})
 * share, so that for 60 s it is asked after mempool.space. Of one call alone ({@code
 * createInvoice}, {@code getStatus}, one {@code waitForPayment}): never kept between calls.
 */
public final class CallExplorer {
  /**
   * When the nodes a call read failed a read, by node URL (milliseconds of the client's clock): of
   * one call alone, shared by its reads, which may run at once.
   */
  public static final class Failures {
    private final Map<String, Long> at = new ConcurrentHashMap<>();

    /** No failure yet: a call starts. */
    public Failures() {}
  }

  /** The names of {@link #explorersToAsk}: the merchant's Bitcoin node. */
  public static final String NODE = "node";

  /** And mempool.space. */
  public static final String MEMPOOL = "mempool";

  private final BitcoinNode node;
  private final Explorer.Source nodeSource;
  private final Failures failures;
  private final LongSupplier nowMs;

  /**
   * The explorer of a call.
   *
   * @param node the channel's node (valid), or null: mempool.space
   * @param failures the call's
   * @param nowMs the clock, in milliseconds
   */
  public CallExplorer(BitcoinNode node, Failures failures, LongSupplier nowMs) {
    NodeUrl url = node == null ? null : NodeUrl.parse(node.url());
    this.node = url == null ? null : node;
    this.nodeSource = url == null ? null : Explorer.Source.node(url);
    this.failures = failures;
    this.nowMs = nowMs;
  }

  /**
   * mempool.space alone: the explorer without a node.
   *
   * @return the explorer
   */
  public static CallExplorer mempool() {
    return new CallExplorer(null, new Failures(), System::currentTimeMillis);
  }

  /**
   * The node this explorer reads, or null for mempool.space.
   *
   * @return the node
   */
  public BitcoinNode node() {
    return node;
  }

  /**
   * The explorers one read asks, in order (SPEC.md §31, {@code fallback}): without a node,
   * mempool.space; with {@code "none"}, the node alone; with {@code "mempool"}, the node then
   * mempool.space, or mempool.space first when the node failed a read less than 60 s ago in the
   * same call ({@code failedRecently}). The next one is asked only when the one before failed: a
   * read gives the answer of the first that answers, and fails when every one asked failed.
   *
   * @param fallback the node's fallback, or null without a node
   * @param failedRecently whether the node failed a read less than 60 s ago in the same call
   * @return {@link #NODE} and {@link #MEMPOOL}, in order
   */
  public static List<String> explorersToAsk(BitcoinNode.Fallback fallback, boolean failedRecently) {
    if (fallback == null) return List.of(MEMPOOL);
    if (fallback == BitcoinNode.Fallback.NONE) return List.of(NODE);
    return failedRecently ? List.of(MEMPOOL, NODE) : List.of(NODE, MEMPOOL);
  }

  /**
   * The explorers the next read asks, in order ({@link #explorersToAsk}).
   *
   * @return the explorers
   */
  public List<Explorer.Source> sources() {
    if (node == null) return List.of(Explorer.Source.MEMPOOL);
    Long failedAt = failures.at.get(node.url());
    boolean failedRecently =
        failedAt != null && nowMs.getAsLong() - failedAt < Constants.NODE_FAILED_RECENTLY_MS;
    List<Explorer.Source> sources = new ArrayList<>();
    for (String name : explorersToAsk(node.fallback(), failedRecently)) {
      sources.add(name.equals(NODE) ? nodeSource : Explorer.Source.MEMPOOL);
    }
    return List.copyOf(sources);
  }

  /** The node failed a read (or its WebSocket a connection): remembered for the call. */
  public void nodeFailed() {
    if (node != null) failures.at.put(node.url(), nowMs.getAsLong());
  }

  /**
   * One read of the explorer: {@code run} with each explorer of {@link #sources()}, in order, until
   * one answers. What {@code run} asks of one explorer at once (the chain's height with the
   * address's transactions) is one read: it fails when either fails, what is still running is then
   * cancelled (its requests aborted), and the whole read is asked again of the next explorer, never
   * part of it.
   *
   * @param run the read of one explorer
   * @param <T> what it gives
   * @return the answer of the first explorer that answers
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when every explorer asked failed
   */
  public <T> T read(Function<Explorer.Source, CompletableFuture<T>> run)
      throws InterruptedException {
    List<NetworkException> failed = new ArrayList<>();
    for (Explorer.Source source : sources()) {
      CompletableFuture<T> answer = run.apply(source);
      try {
        return Tasks.await(answer);
      } catch (NetworkException e) {
        answer.cancel(true);
        if (source.isNode()) nodeFailed();
        failed.add(e);
      }
    }
    if (failed.size() == 1) throw failed.get(0);
    List<String> messages = new ArrayList<>();
    for (NetworkException error : failed) messages.add(error.getMessage());
    throw new NetworkException(
        "Neither the merchant's Bitcoin node nor mempool.space answered ("
            + String.join("; ", messages)
            + ")",
        failed.get(failed.size() - 1));
  }
}
