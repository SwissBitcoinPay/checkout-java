package me.sattle.checkout.internal.nostr;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.sattle.checkout.NetworkException;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.json.JsonObject;

/**
 * The blocking side of a call's relays: a read waited for, a record published on a majority of a
 * list (SPEC.md §13), each failure a {@link NetworkException} with what failed.
 */
public final class Quorum {
  private Quorum() {}

  /**
   * Publishes {@code event} on every relay of {@code relays}, waiting until a majority took it.
   *
   * @param pool the call's relays
   * @param relays the list
   * @param event the event
   * @param authKey the key of NIP-42 authentication: the event's author's
   * @param failure the message of the error when fewer than a majority took it
   * @throws InterruptedException when the call is cancelled
   * @throws NetworkException when fewer than a majority took it
   */
  public static void publish(
      RelayPool pool, List<String> relays, NostrEvent event, byte[] authKey, String failure)
      throws InterruptedException {
    Tasks.await(publishAsync(pool, relays, event, authKey, failure));
  }

  /**
   * Publishes {@code event} on every relay of {@code relays}, at once.
   *
   * @param pool the call's relays
   * @param relays the list
   * @param event the event
   * @param authKey the key of NIP-42 authentication: the event's author's
   * @param failure the message of the error when fewer than a majority took it
   * @return completes once a majority took it; a {@link NetworkException} otherwise; cancelled,
   *     nothing sent, when this thread is interrupted; cancelling it ends the publication
   */
  public static CompletableFuture<Void> publishAsync(
      RelayPool pool, List<String> relays, NostrEvent event, byte[] authKey, String failure) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    // A call stopped (its thread interrupted: cancelled, or its waitForPayment over) publishes
    // nothing more; whoever waits for this future learns of the interrupt.
    if (Thread.currentThread().isInterrupted()) {
      result.cancel(false);
      return result;
    }
    CompletableFuture<Void> publishing =
        pool.publishToQuorum(relays, event, authKey, RelayPool.majority(relays.size()));
    publishing.whenComplete((ignored, error) -> {
      if (error == null) result.complete(null);
      else result.completeExceptionally(new NetworkException(failure, Tasks.unwrap(error)));
    });
    // Nobody waits for it any more (cancelled): the publication is over, its relays asked nothing
    // more.
    result.whenComplete((ignored, error) -> {
      if (result.isCancelled()) publishing.cancel(false);
    });
    return result;
  }

  /**
   * What every relay of {@code relays} holds that matches {@code filter}, waited for ({@link
   * RelayPool#queryRelays}).
   *
   * @param pool the call's relays
   * @param relays the list
   * @param filter the NIP-01 filter
   * @param authKey the key of NIP-42 authentication
   * @param paged whether the read goes page after page
   * @param keep what is kept of each event
   * @param everyRelay whether the read waits for every relay
   * @return each relay's events, and how many answered
   * @throws InterruptedException when the call is cancelled
   */
  public static RelayPool.Answers query(
      RelayPool pool,
      List<String> relays,
      JsonObject filter,
      byte[] authKey,
      boolean paged,
      Keep keep,
      boolean everyRelay)
      throws InterruptedException {
    return Tasks.await(
        pool.queryRelays(
            relays, filter, authKey, RelayPool.majority(relays.size()), paged, keep, everyRelay));
  }
}
