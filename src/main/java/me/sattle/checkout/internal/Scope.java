package me.sattle.checkout.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * What one call started (its forks, its requests): when the call ends, whatever it still runs that
 * nobody waits for is cancelled (an interrupted call stops its requests), and the call returns once
 * each of its forks has ended: nothing it started runs on after it (SPEC.md §1, §23).
 */
public final class Scope implements AutoCloseable {
  private final List<CompletableFuture<?>> started = new ArrayList<>();
  private final List<Tasks.Fork<?>> forks = new ArrayList<>();
  private boolean closed;

  /** A new scope. */
  public Scope() {}

  /**
   * Starts {@code callable} on a thread of its own, at the same time as the call.
   *
   * @param callable what to run
   * @param <T> its value
   * @return the fork
   */
  public <T> Tasks.Fork<T> fork(Callable<T> callable) {
    Tasks.Fork<T> fork = Tasks.Fork.start(callable);
    synchronized (this) {
      forks.removeIf(Tasks.Fork::hasEnded);
      forks.add(fork);
    }
    track(fork.future());
    return fork;
  }

  /**
   * Cancels {@code future} when the call ends, if it is still running then.
   *
   * @param future a request of the call
   * @param <T> its value
   * @return the future
   */
  public <T> CompletableFuture<T> track(CompletableFuture<T> future) {
    boolean cancel;
    synchronized (this) {
      cancel = closed;
      if (!closed) {
        started.removeIf(CompletableFuture::isDone);
        started.add(future);
      }
    }
    if (cancel) future.cancel(true);
    return future;
  }

  /**
   * Cancels what still runs, then waits until each fork has ended (an interrupt of this thread kept
   * for the caller): a cancelled fork stops at its next wait, and starts no request nor publication
   * once stopped.
   */
  @Override
  public void close() {
    List<CompletableFuture<?>> running;
    List<Tasks.Fork<?>> all;
    synchronized (this) {
      closed = true;
      running = new ArrayList<>(started);
      started.clear();
      all = new ArrayList<>(forks);
      forks.clear();
    }
    for (CompletableFuture<?> future : running) future.cancel(true);
    for (Tasks.Fork<?> fork : all) fork.awaitEnd();
  }
}
