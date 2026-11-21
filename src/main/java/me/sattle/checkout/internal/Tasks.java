package me.sattle.checkout.internal;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads of the package: a pool of daemon threads for the parts of a call that run at the same
 * time (the forks) and for the event loops of the relays of each call, and one daemon thread of
 * timers. Daemon threads, idle ones ending after a minute: there is nothing to close.
 *
 * <p>A blocking wait is interruptible: an interrupted call cancels what it waits for, and fails
 * with {@link InterruptedException}, never with an error of SPEC.md §5.
 */
public final class Tasks {
  private Tasks() {}

  private static final AtomicInteger THREADS = new AtomicInteger();

  private static ThreadFactory daemons(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name + "-" + THREADS.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /** Threads for forks and event loops: as many as needed, each idle one ending after 60 s. */
  public static final ExecutorService WORKERS =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          60,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          daemons("sattleme-checkout"));

  private static final ScheduledExecutorService TIMERS = timers();

  private static ScheduledExecutorService timers() {
    ScheduledThreadPoolExecutor timers =
        new ScheduledThreadPoolExecutor(1, daemons("sattleme-checkout-timer"));
    timers.setRemoveOnCancelPolicy(true);
    timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return timers;
  }

  /**
   * Runs {@code task} once, {@code delayMs} from now, on the timer thread: it must not block (it
   * hands work to an event loop).
   *
   * @param delayMs the delay, in milliseconds
   * @param task what to run
   * @return the timer, to cancel
   */
  public static ScheduledFuture<?> schedule(long delayMs, Runnable task) {
    return TIMERS.schedule(task, delayMs, TimeUnit.MILLISECONDS);
  }

  /**
   * What {@code future} completes with, waited for; when this thread is interrupted, the future is
   * cancelled and the interruption thrown.
   *
   * @param future what to wait for
   * @param <T> its value
   * @return its value
   * @throws InterruptedException when this thread was interrupted
   * @throws CancellationException when the future was cancelled
   */
  public static <T> T await(CompletableFuture<T> future) throws InterruptedException {
    // Interrupted already (in a step that ignored it): cancelled, whatever the future gave.
    if (Thread.interrupted()) {
      future.cancel(true);
      throw new InterruptedException("The call was cancelled");
    }
    try {
      return future.get();
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      if (Thread.interrupted()) {
        InterruptedException cancelled = new InterruptedException("The call was cancelled");
        cancelled.initCause(e.getCause());
        throw cancelled;
      }
      throw rethrown(e.getCause());
    }
  }

  /**
   * A future that completes once every one of {@code futures} completed, or fails as soon as one of
   * them fails (with its failure), as {@code Promise.all} does; then, or once it is cancelled, the
   * others are cancelled: nobody waits for them any more, and nothing a call started runs on
   * unawaited (an HTTP request aborted, a fork interrupted).
   *
   * @param futures the futures
   * @return when they are all done
   */
  public static CompletableFuture<Void> all(List<? extends CompletableFuture<?>> futures) {
    CompletableFuture<Void> all = new CompletableFuture<>();
    if (futures.isEmpty()) {
      all.complete(null);
      return all;
    }
    AtomicInteger left = new AtomicInteger(futures.size());
    for (CompletableFuture<?> future : futures) {
      future.whenComplete((value, error) -> {
        if (error != null) all.completeExceptionally(unwrap(error));
        else if (left.decrementAndGet() == 0) all.complete(null);
      });
    }
    all.whenComplete((value, error) -> {
      if (error != null) {
        for (CompletableFuture<?> future : futures) future.cancel(true);
      }
    });
    return all;
  }

  /**
   * {@code source}'s value through {@code function}, as {@link CompletableFuture#thenApply}; but
   * cancelling the result cancels {@code source} too (a request is aborted).
   *
   * @param source the source
   * @param function what its value becomes (it may throw)
   * @param <A> the source's value
   * @param <B> the result's
   * @return the result
   */
  public static <A, B> CompletableFuture<B> map(
      CompletableFuture<A> source, java.util.function.Function<? super A, ? extends B> function) {
    CompletableFuture<B> result = source.thenApply(function);
    result.whenComplete((value, error) -> {
      if (result.isCancelled()) source.cancel(true);
    });
    return result;
  }

  /**
   * The failure a {@link CompletionException} wraps, or {@code error} itself.
   *
   * @param error a failure of a future
   * @return what failed
   */
  public static Throwable unwrap(Throwable error) {
    return error instanceof CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
  }

  /**
   * The failure of a future or a fork, to throw as it is: a {@link RuntimeException} (an error of
   * the package among them) or an {@link Error}; an interruption of a fork is a cancellation.
   *
   * @param cause the failure
   * @return it, to throw
   */
  public static RuntimeException rethrown(Throwable cause) {
    if (cause instanceof RuntimeException) return (RuntimeException) cause;
    if (cause instanceof Error) throw (Error) cause;
    if (cause instanceof InterruptedException) {
      CancellationException cancelled = new CancellationException("Interrupted");
      cancelled.initCause(cause);
      return cancelled;
    }
    return new IllegalStateException(cause);
  }

  /**
   * A part of a call that runs at the same time as the others, on a thread of its own. Its future
   * completes with its value or its failure; cancelling the future interrupts its thread.
   *
   * @param <T> its value
   */
  public static final class Fork<T> {
    private final CompletableFuture<T> result = new CompletableFuture<>();

    /** Completes once its thread is done with it: run, failed, or cancelled (then not run). */
    private final CompletableFuture<Void> ended = new CompletableFuture<>();

    private final FutureTask<T> task;

    private Fork(Callable<T> callable) {
      this.task =
          new FutureTask<>(callable) {
            @Override
            public void run() {
              try {
                super.run();
              } finally {
                ended.complete(null);
              }
            }

            @Override
            protected void done() {
              if (isCancelled()) {
                result.cancel(false);
                return;
              }
              try {
                result.complete(get());
              } catch (ExecutionException e) {
                result.completeExceptionally(e.getCause());
              } catch (InterruptedException | CancellationException e) {
                result.completeExceptionally(e);
              }
            }
          };
      result.whenComplete((value, error) -> {
        if (result.isCancelled()) task.cancel(true);
      });
    }

    /**
     * Starts {@code callable} on a thread of its own.
     *
     * @param callable what to run
     * @param <T> its value
     * @return the fork
     */
    public static <T> Fork<T> start(Callable<T> callable) {
      Fork<T> fork = new Fork<>(callable);
      WORKERS.execute(fork.task);
      return fork;
    }

    /**
     * Its outcome, as a future: cancelling it interrupts the fork.
     *
     * @return the future
     */
    public CompletableFuture<T> future() {
      return result;
    }

    /**
     * Its value, waited for; when this thread is interrupted, the fork is interrupted too.
     *
     * @return its value
     * @throws InterruptedException when this thread was interrupted
     */
    public T join() throws InterruptedException {
      return await(result);
    }

    /**
     * Its outcome, waited for: its value or its failure, never thrown but for an interruption.
     *
     * @return the outcome
     * @throws InterruptedException when this thread was interrupted
     */
    public Settled<T> settle() throws InterruptedException {
      try {
        return Settled.ok(join());
      } catch (RuntimeException e) {
        return Settled.failed(e);
      }
    }

    /** Interrupts it, when it is still running: its value is not needed. */
    public void cancel() {
      result.cancel(true);
    }

    /**
     * Whether its thread is done with it.
     *
     * @return true once it ended
     */
    public boolean hasEnded() {
      return ended.isDone();
    }

    /**
     * Waits until its thread is done with it, whatever an interrupt of this thread says (kept for
     * the caller): a cancelled fork stops at its next wait, then ends; its future is cancelled at
     * once, before that.
     */
    public void awaitEnd() {
      boolean interrupted = false;
      while (true) {
        try {
          ended.get();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException e) {
          break;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /**
   * The outcome of {@code future}, as a future that never fails (but for an {@link Error}).
   *
   * @param future the future
   * @param <T> its value
   * @return its outcome
   */
  public static <T> CompletableFuture<Settled<T>> settle(CompletableFuture<T> future) {
    CompletableFuture<Settled<T>> settled = new CompletableFuture<>();
    future.whenComplete((value, error) -> {
      if (error == null) {
        settled.complete(Settled.ok(value));
        return;
      }
      Throwable cause = unwrap(error);
      if (cause instanceof RuntimeException)
        settled.complete(Settled.failed((RuntimeException) cause));
      else if (cause instanceof Error) settled.completeExceptionally(cause);
      else settled.complete(Settled.failed(rethrown(cause)));
    });
    settled.whenComplete((value, error) -> {
      if (settled.isCancelled()) future.cancel(true);
    });
    return settled;
  }

  /**
   * Runs {@code callable} on a thread of its own, as an asynchronous call: cancelling the future
   * interrupts it, and so cancels the call.
   *
   * @param callable the call
   * @param <T> its value
   * @return its outcome
   */
  public static <T> CompletableFuture<T> async(Callable<T> callable) {
    return Fork.start(callable).future();
  }

  /**
   * The outcome of a part: its value, or the error it failed with.
   *
   * @param value its value, when it succeeded
   * @param error its error, when it failed
   * @param <T> its value
   */
  public record Settled<T>(T value, RuntimeException error) {
    /**
     * A success.
     *
     * @param value the value
     * @param <T> its type
     * @return the outcome
     */
    public static <T> Settled<T> ok(T value) {
      return new Settled<>(value, null);
    }

    /**
     * A failure.
     *
     * @param error the error
     * @param <T> the value's type
     * @return the outcome
     */
    public static <T> Settled<T> failed(RuntimeException error) {
      return new Settled<>(null, error);
    }

    /**
     * Whether it succeeded.
     *
     * @return true for a value
     */
    public boolean ok() {
      return error == null;
    }
  }
}
