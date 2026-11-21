package me.sattle.checkout.internal.nostr;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.sattle.checkout.WebSocketConnection;
import me.sattle.checkout.WebSocketFactory;
import me.sattle.checkout.internal.Constants;
import me.sattle.checkout.internal.Hex;
import me.sattle.checkout.internal.RandomBytes;
import me.sattle.checkout.internal.SerialExecutor;
import me.sattle.checkout.internal.Tasks;
import me.sattle.checkout.internal.Text;
import me.sattle.checkout.internal.Urls;
import me.sattle.checkout.internal.json.Json;
import me.sattle.checkout.internal.json.JsonArray;
import me.sattle.checkout.internal.json.JsonObject;
import me.sattle.checkout.internal.json.JsonValue;

/**
 * The relays of one call (SPEC.md §13, NIP-01, NIP-42): one connection per relay, opened at its
 * first request, used by every request of the call, closed by {@link #close} or {@link #release}.
 * Every relay of a list at once; a read goes on as soon as a majority answered (any two majorities
 * of one list share a relay); a read on every relay waits for each, one timeout more at most after
 * a majority, never for a relay that failed earlier in the call. Each request (a page, a write),
 * the wait for the connection included, is bounded by the relay timeout. Subscription ids are
 * random: what a relay receives names nothing of the integration.
 *
 * <p>Its state lives on its own event loop: every method hands its work to the loop and returns a
 * future the loop completes. None blocks. A call stopped (its thread interrupted) asks nothing
 * more: its reads and publications are cancelled before anything is sent.
 */
public final class RelayPool {
  /**
   * What a read gave (SPEC.md §13): the events of each relay that sent some (on every relay, the
   * pages a relay still reading had sent by then too), and how many relays answered: those whose
   * read ended, never one still reading.
   *
   * @param events each relay's events, by relay
   * @param answered how many relays' reads ended
   * @param outcome whether a majority of the list answered ({@link #readOutcome}): else the read is
   *     no answer ({@code network})
   */
  public record Answers(Map<String, List<Object>> events, int answered, Outcome outcome) {}

  /**
   * The answers of a complete read: each relay's events (as {@link Answers#events}), how many
   * relays read completely, and how many answered (their read ended, complete or not).
   *
   * @param answers each relay's events, by relay
   * @param complete how many relays read completely
   * @param answered how many relays' reads ended
   * @param outcome whether a majority of the list answered, and read completely ({@link
   *     #readOutcome})
   */
  public record CompleteAnswers(
      Map<String, List<Object>> answers, int complete, int answered, Outcome outcome) {}

  /** How one page of a complete read ends: the next page's {@code until}, or how it ended. */
  public record PageEnd(Long until, boolean complete) {}

  /**
   * What the read of one relay of a list is (SPEC.md §13): still reading; ended and complete (of a
   * read that is not a complete read, any read that ended); ended, not complete; failed.
   */
  public enum RelayState {
    /** Still reading: it has not answered, whatever pages it sent. */
    READING,
    /** Its read ended, complete (a read that is not a complete read: it ended). */
    COMPLETE,
    /** Its read ended, not complete. */
    INCOMPLETE,
    /** Its read failed (or the relay failed earlier in the call). */
    FAILED
  }

  /**
   * Of a read of a list, from what each relay's read is: how many answered (their read ended,
   * complete or not: a relay still reading never did, whatever pages it sent), how many read
   * completely, and how many are over (answered or failed).
   *
   * @param answered the relays whose read ended
   * @param complete the relays that read completely
   * @param settled the relays whose read ended or failed
   */
  public record Tally(int answered, int complete, int settled) {}

  /**
   * The tally of a read of a list from what each relay's read is: what the pool decides a read
   * with, and what the vectors of relay-reads.json are run through.
   *
   * @param states each relay's state
   * @return the tally
   */
  public static Tally tally(Iterable<RelayState> states) {
    int answered = 0;
    int complete = 0;
    int settled = 0;
    for (RelayState state : states) {
      if (state == RelayState.COMPLETE || state == RelayState.INCOMPLETE) answered += 1;
      if (state == RelayState.COMPLETE) complete += 1;
      if (state != RelayState.READING) settled += 1;
    }
    return new Tally(answered, complete, settled);
  }

  /**
   * What a read of a list gives once it goes on (SPEC.md §13).
   *
   * @param answered whether a majority of the list answered (else the read is no answer, {@code
   *     network})
   * @param complete whether a majority read completely (a complete read is then complete)
   */
  public record Outcome(boolean answered, boolean complete) {}

  /**
   * What a read of a list gives once it goes on, from what the read of each relay of the list is
   * then: the pool's decision ({@link Answers#answered}, {@link CompleteAnswers}).
   *
   * @param states each relay's state, one per relay of the list
   * @return whether a majority answered, and whether a majority read completely
   */
  public static Outcome readOutcome(List<RelayState> states) {
    int needed = majority(states.size());
    Tally tally = tally(states);
    return new Outcome(tally.answered() >= needed, tally.complete() >= needed);
  }

  private static final String AUTH_REQUIRED = "auth-required:";

  private final WebSocketFactory sockets;
  private final long timeoutMs;
  private final RandomBytes random;
  private final LongSupplier now;
  private final SerialExecutor loop = new SerialExecutor();
  private final Map<String, Connection> connections = new HashMap<>();

  /** Requests not settled yet: {@link #release} waits for them. */
  private final Set<CompletableFuture<?>> pending = new HashSet<>();

  /** Relays a request to which ran out of time or lost its connection in this call. */
  private final Set<String> failed = new HashSet<>();

  /** The reads under way: the call's end ends them ({@link #release}, {@link #close}). */
  private final Set<Read> reads = new HashSet<>();

  /** A read under way, on the loop. */
  private interface Read {
    /** Ends it now, with what it has: its relays' requests end, they are asked nothing more. */
    void finish();
  }

  private boolean closed;

  /**
   * The relays of a call.
   *
   * @param sockets opens the connections
   * @param timeoutMs the bound of each request, in milliseconds
   * @param random the subscription ids and the NIP-42 auxiliary data
   * @param now the clock, in Unix seconds: the date of NIP-42 authentication events
   */
  public RelayPool(WebSocketFactory sockets, long timeoutMs, RandomBytes random, LongSupplier now) {
    this.sockets = sockets;
    this.timeoutMs = timeoutMs;
    this.random = random;
    this.now = now;
  }

  /** What stops the requests of one read once it is over (the others' reads dropped). */
  static final class Stop {
    private boolean stopped;
    private final List<Runnable> listeners = new ArrayList<>();

    void stop() {
      if (stopped) return;
      stopped = true;
      for (Runnable listener : new ArrayList<>(listeners)) listener.run();
      listeners.clear();
    }

    boolean stopped() {
      return stopped;
    }

    Runnable onStop(Runnable listener) {
      if (stopped) {
        listener.run();
        return () -> {};
      }
      listeners.add(listener);
      return () -> listeners.remove(listener);
    }
  }

  /**
   * What every relay of {@code relays} holds that matches {@code filter}, at once, page after page
   * when {@code paged} ({@code until}, for a filter that may match more than a relay sends at
   * once). Completes as soon as {@code needed} relays answered (the others' reads dropped), or once
   * every relay answered or failed; with {@code everyRelay}, once every relay answered or failed,
   * or one timeout after {@code needed} did, keeping the pages a relay still reading sent by then
   * (as events: it did not answer). The events each relay sent, as {@code keep} kept them. Never
   * fails.
   *
   * @param relays the relays
   * @param filter the NIP-01 filter
   * @param authKey the key of NIP-42 authentication
   * @param needed how many relays are a majority
   * @param paged whether the read goes page after page
   * @param keep what is kept of each event
   * @param everyRelay whether the read waits for every relay (SPEC.md §13)
   * @return each relay's events, and how many answered
   */
  public CompletableFuture<Answers> queryRelays(
      List<String> relays,
      JsonObject filter,
      byte[] authKey,
      int needed,
      boolean paged,
      Keep keep,
      boolean everyRelay) {
    CompletableFuture<Answers> result = new CompletableFuture<>();
    if (callStopped(result)) return result;
    loop.execute(() -> {
      RelaysRead read =
          new RelaysRead(relays, filter, authKey, needed, paged, keep, everyRelay, result);
      endsWhenCancelled(result, read);
      read.start();
    });
    return result;
  }

  /**
   * Whether the call asking is stopped (its thread interrupted: cancelled, or its waitForPayment
   * over): then it asks nothing more of the relays, {@code result} cancelled; whoever waits for it
   * learns of the interrupt.
   */
  private static boolean callStopped(CompletableFuture<?> result) {
    if (!Thread.currentThread().isInterrupted()) return false;
    result.cancel(false);
    return true;
  }

  /**
   * Has {@code finish} end a read, on the loop, once nobody waits for it ({@code result} cancelled:
   * the part of the call that read was interrupted) or the call is done ({@link #release}, {@link
   * #close}): its relays are asked nothing more (SPEC.md §13).
   */
  private void endsWhenCancelled(CompletableFuture<?> result, Read read) {
    reads.add(read);
    result.whenComplete((ignored, error) -> {
      if (result.isCancelled()) loop.execute(read::finish);
    });
  }

  /** Ends every read under way, on the loop. */
  private void endReads() {
    for (Read read : new ArrayList<>(reads)) read.finish();
    reads.clear();
  }

  /** One read of a list: the state of {@link #queryRelays}, on the loop. */
  private final class RelaysRead implements Read {
    private final List<String> relays;
    private final JsonObject filter;
    private final byte[] authKey;
    private final int needed;
    private final boolean paged;
    private final Keep keep;
    private final boolean everyRelay;
    private final CompletableFuture<Answers> result;
    private final Map<String, List<Object>> answers = new LinkedHashMap<>();
    private final Stop stop = new Stop();
    private final Map<String, List<Object>> sent = new LinkedHashMap<>();

    /** What each relay's read is: a read that ended is complete (this is no complete read). */
    private final Map<String, RelayState> states = new LinkedHashMap<>();

    private boolean done;
    private ScheduledFuture<?> grace;

    RelaysRead(
        List<String> relays,
        JsonObject filter,
        byte[] authKey,
        int needed,
        boolean paged,
        Keep keep,
        boolean everyRelay,
        CompletableFuture<Answers> result) {
      this.relays = relays;
      this.filter = filter;
      this.authKey = authKey;
      this.needed = needed;
      this.paged = paged;
      this.keep = keep;
      this.everyRelay = everyRelay;
      this.result = result;
    }

    void start() {
      List<String> asked = new ArrayList<>();
      for (String relay : relays) {
        // On every relay: those that failed in this call count as failed at once.
        boolean ask = !everyRelay || !failed.contains(relay);
        if (ask) asked.add(relay);
        states.put(relay, ask ? RelayState.READING : RelayState.FAILED);
      }
      for (String relay : asked) sent.put(relay, new ArrayList<>());
      check();
      for (String relay : asked) {
        CompletableFuture<List<Object>> read =
            paged
                ? pages(relay, filter, authKey, keep, stop, sent.get(relay))
                : query(relay, filter, authKey, keep, stop).thenApply(RelayPool::keptOf);
        read.whenComplete((events, error) -> {
          if (done) return;
          if (error == null) {
            answers.put(relay, events);
            states.put(relay, RelayState.COMPLETE);
          } else {
            states.put(relay, RelayState.FAILED);
          }
          check();
        });
      }
    }

    private void check() {
      if (done) return;
      Tally tally = tally(states.values());
      if (tally.settled() >= relays.size()) {
        finish();
      } else if (tally.answered() >= needed) {
        // On every relay, the others are waited for one timeout more at most.
        if (!everyRelay) finish();
        else if (grace == null) grace = Tasks.schedule(timeoutMs, () -> loop.execute(this::finish));
      }
    }

    @Override
    public void finish() {
      if (done) return;
      done = true;
      reads.remove(this);
      if (grace != null) grace.cancel(false);
      stop.stop();
      // Answered: the relays whose read ended. A relay still reading did not answer: its pages
      // are kept as events, and that is all (SPEC.md §13); of a read that is not paged, a relay
      // still reading sent no page that ended.
      List<RelayState> of = new ArrayList<>(states.values());
      Map<String, List<Object>> events = new LinkedHashMap<>(answers);
      if (everyRelay) {
        for (Map.Entry<String, List<Object>> relay : sent.entrySet()) {
          if (states.get(relay.getKey()) == RelayState.READING && !relay.getValue().isEmpty()) {
            events.put(relay.getKey(), relay.getValue());
          }
        }
      }
      result.complete(new Answers(events, tally(of).answered(), readOutcome(of)));
    }
  }

  /**
   * A complete read of {@code filter} on every relay of {@code relays} at once (SPEC.md §13,
   * "Complete reads"): pages of 100 events, the next from the oldest second of the last, until a
   * page is not full. Completes as soon as {@code needed} relays read completely (the others' reads
   * dropped), or once every relay's read ended or failed; with {@code everyRelay}, as {@link
   * #queryRelays} does, and also as soon as a majority can no longer read completely. Never fails.
   *
   * @param relays the relays
   * @param filter the NIP-01 filter
   * @param authKey the key of NIP-42 authentication
   * @param needed how many relays are a majority
   * @param keep what is kept of each event
   * @param everyRelay whether the read waits for every relay
   * @return each relay's events (of a read that ended, complete or not), and how many read all
   */
  public CompletableFuture<CompleteAnswers> queryComplete(
      List<String> relays,
      JsonObject filter,
      byte[] authKey,
      int needed,
      Keep keep,
      boolean everyRelay) {
    CompletableFuture<CompleteAnswers> result = new CompletableFuture<>();
    if (callStopped(result)) return result;
    loop.execute(() -> {
      CompleteRead read =
          new CompleteRead(relays, filter, authKey, needed, keep, everyRelay, result);
      endsWhenCancelled(result, read);
      read.start();
    });
    return result;
  }

  /** One complete read of a list, on the loop. */
  private final class CompleteRead implements Read {
    private final List<String> relays;
    private final JsonObject filter;
    private final byte[] authKey;
    private final int needed;
    private final Keep keep;
    private final boolean everyRelay;
    private final CompletableFuture<CompleteAnswers> result;
    private final Map<String, List<Object>> answers = new LinkedHashMap<>();
    private final Stop stop = new Stop();
    private final Map<String, List<Object>> sent = new LinkedHashMap<>();

    /** What each relay's read is. */
    private final Map<String, RelayState> states = new LinkedHashMap<>();

    private boolean done;
    private ScheduledFuture<?> grace;

    CompleteRead(
        List<String> relays,
        JsonObject filter,
        byte[] authKey,
        int needed,
        Keep keep,
        boolean everyRelay,
        CompletableFuture<CompleteAnswers> result) {
      this.relays = relays;
      this.filter = filter;
      this.authKey = authKey;
      this.needed = needed;
      this.keep = keep;
      this.everyRelay = everyRelay;
      this.result = result;
    }

    void start() {
      List<String> asked = new ArrayList<>();
      for (String relay : relays) {
        boolean ask = !everyRelay || !failed.contains(relay);
        if (ask) asked.add(relay);
        states.put(relay, ask ? RelayState.READING : RelayState.FAILED);
      }
      for (String relay : asked) sent.put(relay, new ArrayList<>());
      check();
      for (String relay : asked) {
        completePages(relay, filter, authKey, keep, stop, sent.get(relay))
            .whenComplete((read, error) -> {
              if (done) return;
              if (error == null) {
                answers.put(relay, read.events());
                states.put(relay, read.complete() ? RelayState.COMPLETE : RelayState.INCOMPLETE);
              } else {
                states.put(relay, RelayState.FAILED);
              }
              check();
            });
      }
    }

    private void check() {
      if (done) return;
      Tally tally = tally(states.values());
      if (tally.settled() >= relays.size()) {
        finish();
      } else if (everyRelay && tally.complete() + (relays.size() - tally.settled()) < needed) {
        // On every relay, no wait once a majority can no longer read completely: not complete.
        finish();
      } else if (tally.complete() >= needed) {
        if (!everyRelay) finish();
        else if (grace == null) grace = Tasks.schedule(timeoutMs, () -> loop.execute(this::finish));
      }
    }

    @Override
    public void finish() {
      if (done) return;
      done = true;
      reads.remove(this);
      if (grace != null) grace.cancel(false);
      stop.stop();
      // Answered: the relays whose read ended, complete or not; one still reading gives its pages
      // as events, and that is all (SPEC.md §13).
      List<RelayState> of = new ArrayList<>(states.values());
      Tally tally = tally(of);
      Map<String, List<Object>> events = new LinkedHashMap<>(answers);
      if (everyRelay) {
        for (Map.Entry<String, List<Object>> relay : sent.entrySet()) {
          if (states.get(relay.getKey()) == RelayState.READING && !relay.getValue().isEmpty()) {
            events.put(relay.getKey(), relay.getValue());
          }
        }
      }
      result.complete(
          new CompleteAnswers(events, tally.complete(), tally.answered(), readOutcome(of)));
    }
  }

  /**
   * Publishes {@code event} to every relay of {@code relays}, at once. Completes once {@code
   * quorum} relays took it (the others, past its majority, go on until {@link #release}, each
   * bounded by its timeout), fails as soon as they no longer can. Failed, or cancelled (nobody
   * waits for it), it is over: its other relays are asked nothing more (SPEC.md §13).
   *
   * @param relays the relays
   * @param event the event
   * @param authKey the key of NIP-42 authentication
   * @param quorum how many relays must take it
   * @return completes when a quorum took it
   */
  public CompletableFuture<Void> publishToQuorum(
      List<String> relays, NostrEvent event, byte[] authKey, int quorum) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    if (callStopped(result)) return result;
    Stop over = new Stop();
    result.whenComplete((ignored, error) -> {
      if (error != null) loop.execute(over::stop);
    });
    loop.execute(() -> {
      int[] taken = {0};
      int[] failures = {0};
      Runnable settle =
          () -> {
            if (taken[0] >= quorum) {
              result.complete(null);
            } else if (relays.size() - failures[0] < quorum) {
              result.completeExceptionally(
                  new RelayException("Fewer than " + quorum + " relays took the event"));
            }
          };
      settle.run();
      for (String relay : relays) {
        publish(relay, event, authKey, over)
            .whenComplete((ignored, error) -> {
              if (error == null) taken[0] += 1;
              else failures[0] += 1;
              settle.run();
            });
      }
    });
    return result;
  }

  /** Closes every connection now: their requests fail. */
  public void close() {
    loop.execute(this::closeNow);
  }

  private void closeNow() {
    closed = true;
    endReads();
    for (Connection connection : connections.values()) connection.close();
    connections.clear();
  }

  /**
   * Closes every connection once the requests still running are over (a write to the relays past
   * the quorum), each bounded by its timeout. Nothing new may be asked of the pool, and a read
   * still under way (its call failed meanwhile) ends at once.
   */
  public void release() {
    loop.execute(() -> {
      closed = true;
      // A read still under way (its call failed meanwhile) ends at once.
      endReads();
      if (pending.isEmpty()) {
        closeNow();
        return;
      }
      CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
          .whenComplete((ignored, error) -> loop.execute(this::closeNow));
    });
  }

  /**
   * One page of a complete read, read (SPEC.md §13, "Complete reads"): what the read asks next (the
   * page's {@code until}), or how it ends. {@code seen} holds the ids of the read so far; the
   * page's new ones are added to it, and what was kept of them to {@code kept}.
   *
   * @param answer the page's entries
   * @param seen the ids read so far
   * @param kept what the read kept so far
   * @param pagesRead the pages read, this one included
   * @return the next page's {@code until}, or how the read ended
   */
  public static PageEnd afterCompletePage(
      List<Entry> answer, Set<String> seen, List<Object> kept, int pagesRead) {
    Long oldest = null;
    boolean fresh = false;
    for (Entry entry : answer) {
      if (entry.id() == null) continue;
      if (entry.createdAt() != null) {
        oldest = oldest == null ? entry.createdAt() : Math.min(oldest, entry.createdAt());
      }
      if (seen.contains(entry.id())) continue;
      seen.add(entry.id());
      if (entry.value() != null) kept.add(entry.value());
      // New, dated or not ("no event not seen before"): the next page may hold more.
      fresh = true;
    }
    // More than asked: the relay ignored the limit, and what it left out cannot be told.
    if (answer.size() > Constants.COMPLETE_READ_LIMIT) return new PageEnd(null, false);
    if (answer.size() < Constants.COMPLETE_READ_LIMIT) return new PageEnd(null, true);
    // Full: the next page from its oldest second, unless it cannot move on.
    if (oldest == null || !fresh || pagesRead >= Constants.MAX_PAGES)
      return new PageEnd(null, false);
    return new PageEnd(oldest, false);
  }

  /**
   * One page of a paged read, read (SPEC.md §13, "Paged reads"): the {@code until} of the next
   * page, or null when the read ends there. Only an event's string {@code id} and safe integer
   * {@code created_at} page the read, whatever else it holds (one that does not check out pages it
   * all the same); an event without them is not kept. {@code seen} holds the ids of the read so
   * far; the page's new ones are added to it, and what was kept of them to {@code kept}.
   *
   * @param answer the page's entries
   * @param seen the ids read so far
   * @param kept what the read kept so far
   * @return the next page's {@code until}: the oldest second of the page's dated events, one second
   *     earlier when none of them was new to the read; null when it held none
   */
  public static Long afterPagedPage(List<Entry> answer, Set<String> seen, List<Object> kept) {
    Long oldest = null;
    boolean fresh = false;
    for (Entry entry : answer) {
      if (entry.id() == null || entry.createdAt() == null) continue;
      oldest = oldest == null ? entry.createdAt() : Math.min(oldest, entry.createdAt());
      if (seen.contains(entry.id())) continue;
      seen.add(entry.id());
      if (entry.value() != null) kept.add(entry.value());
      fresh = true;
    }
    if (oldest == null) return null;
    // Nothing new: the oldest second again would bring the same page.
    return fresh ? oldest : oldest - 1;
  }

  /** More than half of {@code count} relays: any two majorities of one list share a relay. */
  public static int majority(int count) {
    return count / 2 + 1;
  }

  private static List<Object> keptOf(List<Entry> entries) {
    List<Object> kept = new ArrayList<>();
    for (Entry entry : entries) if (entry.value() != null) kept.add(entry.value());
    return kept;
  }

  /** The stored events of {@code url} that match {@code filter}, page after page. */
  private CompletableFuture<List<Object>> pages(
      String url, JsonObject filter, byte[] authKey, Keep keep, Stop stop, List<Object> events) {
    CompletableFuture<List<Object>> result = new CompletableFuture<>();
    Set<String> seen = new HashSet<>();
    page(
        url,
        filter,
        authKey,
        keep,
        stop,
        events,
        seen,
        0,
        Json.safeInteger(filter.get("until")),
        result);
    return result;
  }

  private void page(
      String url,
      JsonObject filter,
      byte[] authKey,
      Keep keep,
      Stop stop,
      List<Object> events,
      Set<String> seen,
      int page,
      Long until,
      CompletableFuture<List<Object>> result) {
    if (page >= Constants.MAX_PAGES) {
      result.complete(events);
      return;
    }
    query(url, until == null ? filter : with(filter, "until", until), authKey, keep, stop)
        .whenComplete((answer, error) -> {
          if (error != null) {
            // A relay that stops answering after its first page keeps what it sent.
            if (page == 0 || stop.stopped()) result.completeExceptionally(error);
            else result.complete(events);
            return;
          }
          Long next = afterPagedPage(answer, seen, events);
          if (next == null) {
            result.complete(events);
            return;
          }
          page(url, filter, authKey, keep, stop, events, seen, page + 1, next, result);
        });
  }

  /** What a relay's complete read gave. */
  private record CompletePages(List<Object> events, boolean complete) {}

  /**
   * The events of {@code url} that match {@code filter}, read completely if it can be: whether it
   * was. Fails when its first page fails (the relay failed); a later failure ends it incomplete.
   */
  private CompletableFuture<CompletePages> completePages(
      String url, JsonObject filter, byte[] authKey, Keep keep, Stop stop, List<Object> events) {
    CompletableFuture<CompletePages> result = new CompletableFuture<>();
    completePage(
        url,
        filter,
        authKey,
        keep,
        stop,
        events,
        new HashSet<>(),
        1,
        Json.safeInteger(filter.get("until")),
        result);
    return result;
  }

  private void completePage(
      String url,
      JsonObject filter,
      byte[] authKey,
      Keep keep,
      Stop stop,
      List<Object> events,
      Set<String> seen,
      int page,
      Long until,
      CompletableFuture<CompletePages> result) {
    JsonObject pageFilter = with(filter, "limit", Constants.COMPLETE_READ_LIMIT);
    if (until != null) pageFilter = with(pageFilter, "until", until);
    query(url, pageFilter, authKey, keep, stop)
        .whenComplete((answer, error) -> {
          if (error != null) {
            if (page == 1 || stop.stopped()) result.completeExceptionally(error);
            else result.complete(new CompletePages(events, false));
            return;
          }
          PageEnd next = afterCompletePage(answer, seen, events, page);
          if (next.until() == null) {
            result.complete(new CompletePages(events, next.complete()));
          } else {
            completePage(
                url, filter, authKey, keep, stop, events, seen, page + 1, next.until(), result);
          }
        });
  }

  /** {@code filter} with the member {@code name} set to {@code value} (in its place, or last). */
  private static JsonObject with(JsonObject filter, String name, long value) {
    JsonObject copy = new JsonObject();
    for (Map.Entry<String, JsonValue> member : filter.entries())
      copy.put(member.getKey(), member.getValue());
    return copy.put(name, value);
  }

  /** The answer of one query: its entries, and the reason of a CLOSED, or null. */
  private record Asked(List<Entry> events, String closed) {}

  /**
   * The stored events of one relay that match {@code filter}, as it sent them: the entry of each
   * (the event itself only when {@code keep} takes it), unchecked. At most 1000. Authenticates when
   * the relay asks (NIP-42).
   */
  private CompletableFuture<List<Entry>> query(
      String url, JsonObject filter, byte[] authKey, Keep keep, Stop stop) {
    return request(
        url,
        stop,
        (connection, onEnd, live) ->
            ask(connection, filter, keep, onEnd)
                .thenCompose(asked -> {
                  // A read that went on without this relay meanwhile, or a request out of
                  // time, asks it nothing more: no authentication, no query again (SPEC.md
                  // §13).
                  if (asked.closed() != null
                      && asked.closed().startsWith(AUTH_REQUIRED)
                      && live.getAsBoolean()) {
                    return connection
                        .authenticate(authKey, now.getAsLong(), random)
                        .thenCompose(ok ->
                            ok && live.getAsBoolean()
                                ? ask(connection, filter, keep, onEnd)
                                : CompletableFuture.completedFuture(asked));
                  }
                  return CompletableFuture.completedFuture(asked);
                })
                .thenApply(asked -> {
                  if (asked.closed() != null) {
                    throw new RelayException(
                        url + " closed the query: " + Text.excerpt(asked.closed()));
                  }
                  return asked.events();
                }));
  }

  /** One REQ: its events until EOSE (or the 1000th), or its CLOSED. */
  private CompletableFuture<Asked> ask(
      Connection connection, JsonObject filter, Keep keep, Consumer<Runnable> onEnd) {
    CompletableFuture<Asked> done = new CompletableFuture<>();
    String subscription = Hex.encode(random.next(8));
    List<Entry> events = new ArrayList<>();
    // The ids of this answer so far: the first event of each is the one read (SPEC.md §13).
    Set<String> read = new HashSet<>();
    boolean[] open = {true};
    Runnable[] stopListening = new Runnable[1];
    java.util.function.BiConsumer<String, Boolean> end =
        (closedReason, byRelay) -> {
          if (!open[0]) return;
          open[0] = false;
          stopListening[0].run();
          // Over this connection, which stays open for the call's next requests.
          if (!byRelay) connection.send(new JsonArray().add("CLOSE").add(subscription));
          done.complete(new Asked(events, closedReason));
        };
    stopListening[0] =
        connection.listen(message -> {
          if (!subscription.equals(Connection.stringAt(message, 1))) return;
          String type = Connection.stringAt(message, 0);
          if ("EVENT".equals(type)) {
            JsonValue event = Connection.at(message, 2);
            events.add(
                Entry.of(
                    event == null ? me.sattle.checkout.internal.json.JsonNull.NULL : event,
                    keep,
                    read));
            if (events.size() >= Constants.MAX_EVENTS_PER_QUERY) end.accept(null, false);
          } else if ("EOSE".equals(type)) {
            end.accept(null, false);
          } else if ("CLOSED".equals(type)) {
            String reason = Connection.stringAt(message, 2);
            end.accept(reason == null ? "" : reason, true);
          }
        });
    onEnd.accept(() -> {
      if (!open[0]) return;
      open[0] = false;
      stopListening[0].run();
      connection.send(new JsonArray().add("CLOSE").add(subscription));
    });
    connection.send(new JsonArray().add("REQ").add(subscription).add(filter));
    return done;
  }

  /**
   * Publishes {@code event} to one relay, authenticating when it asks. Fails unless taken, or once
   * {@code over} stops (its publication over).
   */
  private CompletableFuture<Void> publish(String url, NostrEvent event, byte[] authKey, Stop over) {
    return request(
        url,
        over,
        (connection, onEnd, live) ->
            write(connection, event, onEnd)
                .thenCompose(answer -> {
                  // Past its time, or once the pool closed, a publication writes nothing more.
                  if (!answer.accepted()
                      && answer.reason().startsWith(AUTH_REQUIRED)
                      && live.getAsBoolean()) {
                    return connection
                        .authenticate(authKey, now.getAsLong(), random)
                        .thenCompose(ok ->
                            ok && live.getAsBoolean()
                                ? write(connection, event, onEnd)
                                : CompletableFuture.completedFuture(answer));
                  }
                  return CompletableFuture.completedFuture(answer);
                })
                .thenApply(answer -> {
                  if (!answer.accepted()) {
                    throw new RelayException(
                        url + " refused the event: " + Text.excerpt(answer.reason()),
                        answer.reason());
                  }
                  return null;
                }));
  }

  /** A relay's answer to an EVENT. */
  private record Written(boolean accepted, String reason) {}

  private CompletableFuture<Written> write(
      Connection connection, NostrEvent event, Consumer<Runnable> onEnd) {
    CompletableFuture<Written> done = new CompletableFuture<>();
    Runnable[] stop = new Runnable[1];
    stop[0] =
        connection.listen(message -> {
          if (!"OK".equals(Connection.stringAt(message, 0))
              || !event.id().equals(Connection.stringAt(message, 1))) {
            return;
          }
          stop[0].run();
          String reason = Connection.stringAt(message, 3);
          done.complete(
              new Written(Json.isTrue(Connection.at(message, 2)), reason == null ? "" : reason));
        });
    onEnd.accept(stop[0]);
    connection.send(new JsonArray().add("EVENT").add(event.toJson()));
    return done;
  }

  /** What a request runs once its connection is open. */
  @FunctionalInterface
  private interface Operation<T> {
    CompletableFuture<T> run(Connection connection, Consumer<Runnable> onEnd, BooleanSupplier live);
  }

  /**
   * Runs {@code operation} over the connection to {@code url} once it is open, within the timeout.
   * Fails when the connection ends, the time runs out, or {@code stop} stops (then without marking
   * the relay failed). {@code onEnd} registers what the operation leaves behind (listeners,
   * subscriptions), cleaned up when it settles; {@code live} tells whether it has not settled yet:
   * once it has, the operation starts nothing more (SPEC.md §13: no authentication, no query or
   * write again), its random bytes left alone.
   */
  private <T> CompletableFuture<T> request(String url, Stop stop, Operation<T> operation) {
    CompletableFuture<T> request = new CompletableFuture<>();
    List<Runnable> cleanups = new ArrayList<>();
    boolean[] settled = {false};
    ScheduledFuture<?>[] timer = new ScheduledFuture<?>[1];
    Consumer<Throwable> settleError =
        error -> {
          if (settled[0]) return;
          settled[0] = true;
          if (timer[0] != null) timer[0].cancel(false);
          for (Runnable cleanup : new ArrayList<>(cleanups)) cleanup.run();
          cleanups.clear();
          request.completeExceptionally(error);
        };
    Consumer<T> settleValue =
        value -> {
          if (settled[0]) return;
          settled[0] = true;
          if (timer[0] != null) timer[0].cancel(false);
          for (Runnable cleanup : new ArrayList<>(cleanups)) cleanup.run();
          cleanups.clear();
          request.complete(value);
        };
    Consumer<Throwable> fail =
        error -> {
          if (settled[0]) return;
          failed.add(url);
          settleError.accept(error);
        };
    pending.add(request);
    request.whenComplete((ignored, error) -> pending.remove(request));
    timer[0] =
        Tasks.schedule(
            timeoutMs,
            () -> loop.execute(() -> fail.accept(new RelayException(url + ": no answer in time"))));
    if (stop != null) {
      cleanups.add(
          stop.onStop(() -> settleError.accept(new RelayException(url + ": no longer asked"))));
      if (settled[0]) return request;
    }
    Connection connection;
    try {
      connection = connection(url);
    } catch (RelayException e) {
      fail.accept(e);
      return request;
    }
    cleanups.add(connection.onDeath(fail::accept));
    if (settled[0]) return request;
    connection.opened.whenComplete((ignored, error) -> {
      if (settled[0] || error != null) return;
      CompletableFuture<T> running;
      try {
        running =
            operation.run(
                connection,
                cleanup -> {
                  if (settled[0]) cleanup.run();
                  else cleanups.add(cleanup);
                },
                () -> !settled[0]);
      } catch (RuntimeException e) {
        settleError.accept(e);
        return;
      }
      running.whenComplete((value, failure) -> {
        if (failure != null) settleError.accept(unwrap(failure));
        else settleValue.accept(value);
      });
    });
    return request;
  }

  private static Throwable unwrap(Throwable error) {
    return error instanceof java.util.concurrent.CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
  }

  /**
   * The pool's connection to {@code url}, opened at its first request: from a thread of the
   * package's, never on this loop (a factory may resolve the relay's name, or more, before it
   * returns, and nothing bounds that); the requests' timers hold meanwhile.
   */
  private Connection connection(String url) {
    if (closed) throw new RelayException(url + ": the pool is closed");
    Connection connection = connections.get(url);
    if (connection == null) {
      Connection opening = new Connection(url, loop);
      connection = opening;
      connections.put(url, opening);
      Tasks.WORKERS.execute(() -> {
        WebSocketConnection socket;
        try {
          socket =
              sockets.open(
                  Urls.webSocketUri(url),
                  Map.of("User-Agent", Constants.USER_AGENT),
                  Duration.ofMillis(timeoutMs),
                  opening);
        } catch (RuntimeException e) {
          socket = null;
        }
        WebSocketConnection given = socket;
        loop.execute(() -> opening.attach(given));
      });
    }
    return connection;
  }
}
